from __future__ import annotations

import json
import logging
import math
import os
import re
import shutil
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import ifcopenshell
import ifcopenshell.api
try:
  import ifcopenshell.geom as ifcopenshell_geom
except Exception:  # pragma: no cover - optional geometry module
  ifcopenshell_geom = None
import ifcopenshell.guid
import ifcopenshell.util.element
import ifcopenshell.util.placement
import ifcopenshell.util.unit
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logger = logging.getLogger("ifc_ops")

DATA_ROOT = Path(os.getenv("IFC_OPS_DATA_ROOT", "/data")).resolve()
ELEMENT_STATE_PSET = "Pset_Baka_State"
FURNITURE_STATE_PSET = "Pset_Baka_Furniture"
HISTORY_STATE_PSET = "Pset_Baka_History"
PSET_PROP_KEY_PATTERN = re.compile(r"^pset-(\d+)-(\d+)$")
EDITABLE_DIRECT_ATTRIBUTES = {"Name", "Description", "ObjectType", "Tag", "LongName"}
ROOM_NUMBER_KEYS = {"roomnumber", "raumnummer", "number"}
MOVE_DELTA_CUSTOM_KEY = "__bakaMoveDeltaJson"
ROTATE_DELTA_CUSTOM_KEY = "__bakaRotateDeltaJson"
INVERSE_COORDINATION_MATRIX_CUSTOM_KEY = "__bakaInverseCoordinationMatrixJson"
PLACEMENT_POSITION_CUSTOM_KEY = "__bakaPlacementPositionJson"
SPACE_RELATIVE_POSITION_CUSTOM_KEY = "__bakaSpaceRelativePositionJson"

app = FastAPI(title="ifc-ops", version="0.1.0")
_WORLD_GEOM_SETTINGS: Any | None = None


class Point3D(BaseModel):
  x: float
  y: float
  z: float


class MetadataEntry(BaseModel):
  ifcId: int
  type: str | None = None
  custom: dict[str, Any] | None = None
  position: Point3D | None = None
  moveDelta: Point3D | None = None
  rotation: Point3D | None = None
  rotateDelta: Point3D | None = None
  deleted: bool | None = None
  updatedAt: str | None = None


class FurnitureGeometry(BaseModel):
  positions: list[float] = Field(default_factory=list)
  indices: list[int] = Field(default_factory=list)


class FurnitureItem(BaseModel):
  id: str
  model: str
  name: str | None = None
  position: Point3D
  rotation: Point3D | None = None
  scale: Point3D | None = None
  roomNumber: str | None = None
  spaceIfcId: int | None = None
  custom: dict[str, Any] | None = None
  geometry: FurnitureGeometry | None = None
  updatedAt: str | None = None


class HistoryEntry(BaseModel):
  ifcId: int
  label: str
  timestamp: str


class ImportStateRequest(BaseModel):
  source_ifc_path: str


class ImportStateResponse(BaseModel):
  metadata: list[MetadataEntry] = Field(default_factory=list)
  furniture: list[FurnitureItem] = Field(default_factory=list)
  history: list[HistoryEntry] = Field(default_factory=list)
  warnings: list[str] = Field(default_factory=list)


class ExportStateRequest(BaseModel):
  source_ifc_path: str
  target_ifc_path: str
  metadata: list[MetadataEntry] = Field(default_factory=list)
  furniture: list[FurnitureItem] = Field(default_factory=list)
  history: list[HistoryEntry] = Field(default_factory=list)


class ExportStateResponse(BaseModel):
  target_ifc_path: str
  exported_metadata_count: int
  exported_furniture_count: int
  exported_history_count: int
  warnings: list[str] = Field(default_factory=list)


# Return the current UTC timestamp as an ISO string.
def _now_iso() -> str:
  return datetime.now(UTC).isoformat()


# Check whether a resolved path stays inside the configured data root.
def _is_inside_data_root(path: Path) -> bool:
  try:
    path.resolve().relative_to(DATA_ROOT)
    return True
  except ValueError:
    return False


# Resolve and validate an existing input file path inside the data root.
def _resolve_existing_path(raw_path: str, field_name: str) -> Path:
  if not raw_path:
    raise ValueError(f"{field_name} is required")
  candidate = Path(raw_path)
  if not candidate.is_absolute():
    candidate = DATA_ROOT / candidate
  resolved = candidate.resolve()
  if not _is_inside_data_root(resolved):
    raise ValueError(f"{field_name} must stay inside {DATA_ROOT}")
  if not resolved.is_file():
    raise ValueError(f"{field_name} does not exist: {resolved}")
  return resolved


# Resolve and validate an IFC output path inside the data root.
def _resolve_target_path(raw_path: str) -> Path:
  if not raw_path:
    raise ValueError("target_ifc_path is required")
  candidate = Path(raw_path)
  if not candidate.is_absolute():
    candidate = DATA_ROOT / candidate
  resolved = candidate.resolve()
  if resolved.suffix.lower() != ".ifc":
    raise ValueError("target_ifc_path must use .ifc extension")
  if not _is_inside_data_root(resolved):
    raise ValueError(f"target_ifc_path must stay inside {DATA_ROOT}")
  resolved.parent.mkdir(parents=True, exist_ok=True)
  return resolved


# Fetch an IFC entity by numeric id without throwing on bad input.
def _safe_by_id(model: Any, entity_id: Any) -> Any | None:
  try:
    if entity_id is None:
      return None
    return model.by_id(int(entity_id))
  except Exception:
    return None


# Parse a JSON object safely and collect warnings instead of failing hard.
def _try_json_dict(raw: Any, warnings: list[str], field_name: str) -> dict[str, Any] | None:
  if raw is None:
    return None
  if isinstance(raw, dict):
    return raw
  if not isinstance(raw, str):
    warnings.append(f"{field_name} is not valid JSON text")
    return None
  try:
    parsed = json.loads(raw)
  except json.JSONDecodeError:
    warnings.append(f"{field_name} JSON decode failed")
    return None
  if not isinstance(parsed, dict):
    warnings.append(f"{field_name} JSON is not an object")
    return None
  return parsed


# Convert loose user or IFC values into a boolean when possible.
def _coerce_bool(raw: Any) -> bool | None:
  if isinstance(raw, bool):
    return raw
  if isinstance(raw, (int, float)):
    return bool(raw)
  if isinstance(raw, str):
    normalized = raw.strip().lower()
    if normalized in {"1", "true", "yes", "y"}:
      return True
    if normalized in {"0", "false", "no", "n"}:
      return False
  return None


# Read one property set from an IFC product as a plain dictionary.
def _read_pset_values(product: Any, pset_name: str) -> dict[str, Any] | None:
  psets = ifcopenshell.util.element.get_psets(product)
  values = psets.get(pset_name)
  if not isinstance(values, dict):
    return None
  return {key: value for key, value in values.items() if key != "id"}


# Return an existing property set or create it when it is missing.
def _get_or_create_pset(model: Any, product: Any, pset_name: str) -> Any:
  psets = ifcopenshell.util.element.get_psets(product)
  values = psets.get(pset_name)
  if isinstance(values, dict):
    pset_id = values.get("id")
    if isinstance(pset_id, int):
      existing = _safe_by_id(model, pset_id)
      if existing is not None:
        return existing
  return ifcopenshell.api.run("pset.add_pset", model, product=product, name=pset_name)


# Remove a named property set from an IFC entity if it exists.
def _remove_named_pset(model: Any, entity: Any, pset_name: str) -> bool:
  psets = ifcopenshell.util.element.get_psets(entity)
  values = psets.get(pset_name)
  if not isinstance(values, dict):
    return False

  removed = False
  pset_id = values.get("id")
  pset = _safe_by_id(model, pset_id) if isinstance(pset_id, int) else None
  if pset is None:
    return False

  for inverse in list(model.get_inverse(pset) or []):
    if inverse is None or not hasattr(inverse, "is_a"):
      continue
    try:
      if inverse.is_a("IfcRelDefinesByProperties"):
        model.remove(inverse)
        removed = True
    except Exception:
      continue

  try:
    model.remove(pset)
    removed = True
  except Exception:
    pass

  return removed


# Delete technical editor PSETs before writing the final IFC.
def _purge_editor_state_psets(model: Any) -> int:
  removed = 0
  for product in model.by_type("IfcProduct"):
    if _remove_named_pset(model, product, ELEMENT_STATE_PSET):
      removed += 1

  project = _first_ifc_project(model)
  if project is not None:
    if _remove_named_pset(model, project, FURNITURE_STATE_PSET):
      removed += 1
    if _remove_named_pset(model, project, HISTORY_STATE_PSET):
      removed += 1

  return removed


# Return the first IfcProject entity from the loaded model.
def _first_ifc_project(model: Any) -> Any | None:
  projects = model.by_type("IfcProject")
  if not projects:
    return None
  return projects[0]


# Return editor state imported from IFC, which is currently an empty no-op response.
def _import_state(request: ImportStateRequest) -> ImportStateResponse:
  source_path = _resolve_existing_path(request.source_ifc_path, "source_ifc_path")
  return ImportStateResponse(
    metadata=[],
    furniture=[],
    history=[],
    warnings=[],
  )


# Return the first available IfcOwnerHistory for newly created entities.
def _first_owner_history(model: Any) -> Any | None:
  owners = model.by_type("IfcOwnerHistory")
  return owners[0] if owners else None


# Convert a value to a finite float and fall back to a default on invalid input.
def _safe_float(value: Any, default: float = 0.0) -> float:
  try:
    parsed = float(value)
    if math.isfinite(parsed):
      return parsed
  except (TypeError, ValueError):
    pass
  return default


# Return how many meters correspond to one model unit in the IFC file.
def _meters_per_model_unit(model: Any) -> float:
  try:
    scale = float(ifcopenshell.util.unit.calculate_unit_scale(model))
    if math.isfinite(scale) and scale > 0:
      return scale
  except Exception:
    pass
  return 1.0


# Normalize a dimension value and fall back to a safe positive default.
def _safe_dim(value: Any, default: float = 1.0) -> float:
  parsed = abs(_safe_float(value, default))
  if parsed <= 1e-6:
    return default
  return parsed


# Build IFC placement axes for a furniture item from its viewer rotation.
def _build_furniture_axis_basis(
  rotation: Point3D | None,
) -> tuple[tuple[float, float, float], tuple[float, float, float]]:
  if rotation is None:
    return ((0.0, 0.0, 1.0), (1.0, 0.0, 0.0))

  rx, ry, rz = _viewer_rotation_to_ifc_world(
    _safe_float(rotation.x, 0.0),
    _safe_float(rotation.y, 0.0),
    _safe_float(rotation.z, 0.0),
  )
  axis = _normalize_direction(_rotate_vector_xyz((0.0, 0.0, 1.0), rx, ry, rz), (0.0, 0.0, 1.0))
  ref = _normalize_direction(_rotate_vector_xyz((1.0, 0.0, 0.0), rx, ry, rz), (1.0, 0.0, 0.0))
  ref_proj = _dot3(ref, axis)
  ref = _normalize_direction(
    (
      ref[0] - axis[0] * ref_proj,
      ref[1] - axis[1] * ref_proj,
      ref[2] - axis[2] * ref_proj,
    ),
    (1.0, 0.0, 0.0),
  )
  return (axis, ref)


# Create a new IfcLocalPlacement from an absolute point and optional axes.
def _create_absolute_local_placement(
  model: Any,
  position: Point3D,
  axis: Any = None,
  ref_direction: Any = None,
  placement_rel_to: Any = None,
) -> Any:
  location = model.createIfcCartesianPoint(
    (
      _safe_float(position.x),
      _safe_float(position.y),
      _safe_float(position.z),
    )
  )
  relative = model.createIfcAxis2Placement3D(location, axis, ref_direction)
  return model.createIfcLocalPlacement(placement_rel_to, relative)


# Convert a world-space point into coordinates relative to a parent placement.
def _world_point_to_local_relative(parent_placement: Any, point: Point3D) -> Point3D:
  try:
    matrix = ifcopenshell.util.placement.get_local_placement(parent_placement)
  except Exception:
    return point

  try:
    tx = float(matrix[0][3])
    ty = float(matrix[1][3])
    tz = float(matrix[2][3])
    dx = _safe_float(point.x) - tx
    dy = _safe_float(point.y) - ty
    dz = _safe_float(point.z) - tz
    return Point3D(
      x=float(matrix[0][0]) * dx + float(matrix[1][0]) * dy + float(matrix[2][0]) * dz,
      y=float(matrix[0][1]) * dx + float(matrix[1][1]) * dy + float(matrix[2][1]) * dz,
      z=float(matrix[0][2]) * dx + float(matrix[1][2]) * dy + float(matrix[2][2]) * dz,
    )
  except Exception:
    return point


# Read the rotation matrix encoded in an IFC local placement.
def _placement_rotation_matrix(placement: Any) -> list[list[float]] | None:
  if placement is None:
    return None
  try:
    matrix = ifcopenshell.util.placement.get_local_placement(placement)
  except Exception:
    return None
  if matrix is None:
    return None
  try:
    return [
      [float(matrix[0][0]), float(matrix[0][1]), float(matrix[0][2])],
      [float(matrix[1][0]), float(matrix[1][1]), float(matrix[1][2])],
      [float(matrix[2][0]), float(matrix[2][1]), float(matrix[2][2])],
    ]
  except Exception:
    return None


# Convert a world-space direction vector into a parent placement frame.
def _world_vector_to_local_relative(
  parent_placement: Any,
  vector: tuple[float, float, float],
) -> tuple[float, float, float]:
  rotation = _placement_rotation_matrix(parent_placement)
  if rotation is None:
    return vector
  dx, dy, dz = vector
  return (
    rotation[0][0] * dx + rotation[1][0] * dy + rotation[2][0] * dz,
    rotation[0][1] * dx + rotation[1][1] * dy + rotation[2][1] * dz,
    rotation[0][2] * dx + rotation[1][2] * dy + rotation[2][2] * dz,
  )


# Convert a viewer position into IFC world axes and model units.
def _viewer_point_to_ifc_world(position: Point3D, viewer_to_model_units: float = 1.0) -> Point3D:
  x = _safe_float(position.x) * viewer_to_model_units
  y = _safe_float(position.y) * viewer_to_model_units
  z = _safe_float(position.z) * viewer_to_model_units
  ifc_x, ifc_y, ifc_z = _viewer_delta_to_ifc_world(x, y, z)
  return Point3D(x=ifc_x, y=ifc_y, z=ifc_z)


# Read numeric coordinates from an IFC point-like entity safely.
def _safe_point_coords(entity: Any, expected_len: int = 3) -> tuple[float, ...] | None:
  coords = getattr(entity, "Coordinates", None)
  if coords is None:
    return None
  values = tuple(_safe_float(value) for value in coords)
  if len(values) < expected_len:
    return None
  return values


# Transform a point by a 4x4 affine matrix.
def _transform_point_by_matrix(point: Point3D, matrix: Any) -> Point3D:
  try:
    x = _safe_float(point.x)
    y = _safe_float(point.y)
    z = _safe_float(point.z)
    return Point3D(
      x=float(matrix[0][0]) * x + float(matrix[0][1]) * y + float(matrix[0][2]) * z + float(matrix[0][3]),
      y=float(matrix[1][0]) * x + float(matrix[1][1]) * y + float(matrix[1][2]) * z + float(matrix[1][3]),
      z=float(matrix[2][0]) * x + float(matrix[2][1]) * y + float(matrix[2][2]) * z + float(matrix[2][3]),
    )
  except Exception:
    return point


# Convert an IfcAxis2Placement3D into a 4x4 matrix.
def _axis2placement3d_to_matrix(placement: Any | None) -> list[list[float]] | None:
  if placement is None or not hasattr(placement, "is_a") or not placement.is_a("IfcAxis2Placement3D"):
    return None

  location = getattr(placement, "Location", None)
  coords = _safe_point_coords(location, 3)
  if coords is None:
    return None

  axis_dir = getattr(placement, "Axis", None)
  ref_dir = getattr(placement, "RefDirection", None)
  axis_values = tuple(getattr(axis_dir, "DirectionRatios", (0.0, 0.0, 1.0)) or (0.0, 0.0, 1.0))
  ref_values = tuple(getattr(ref_dir, "DirectionRatios", (1.0, 0.0, 0.0)) or (1.0, 0.0, 0.0))

  axis = _normalize_direction(
    (
      _safe_float(axis_values[0], 0.0),
      _safe_float(axis_values[1], 0.0),
      _safe_float(axis_values[2], 1.0),
    ),
    (0.0, 0.0, 1.0),
  )
  ref = _normalize_direction(
    (
      _safe_float(ref_values[0], 1.0),
      _safe_float(ref_values[1], 0.0),
      _safe_float(ref_values[2], 0.0),
    ),
    (1.0, 0.0, 0.0),
  )

  ref_proj = _dot3(ref, axis)
  ref = _normalize_direction(
    (
      ref[0] - axis[0] * ref_proj,
      ref[1] - axis[1] * ref_proj,
      ref[2] - axis[2] * ref_proj,
    ),
    (1.0, 0.0, 0.0),
  )
  side = _normalize_direction(_cross3(axis, ref), (0.0, 1.0, 0.0))

  return [
    [ref[0], side[0], axis[0], coords[0]],
    [ref[1], side[1], axis[1], coords[1]],
    [ref[2], side[2], axis[2], coords[2]],
    [0.0, 0.0, 0.0, 1.0],
  ]


# Read the local anchor offset from a swept profile definition.
def _read_profile_anchor_xy(profile: Any) -> tuple[float, float] | None:
  if profile is None or not hasattr(profile, "is_a"):
    return None

  if profile.is_a("IfcRectangleProfileDef"):
    position = getattr(profile, "Position", None)
    location = getattr(position, "Location", None)
    coords = _safe_point_coords(location, 2) or (0.0, 0.0)
    return (_safe_float(coords[0]), _safe_float(coords[1]))

  outer_curve = getattr(profile, "OuterCurve", None)
  if outer_curve is None or not hasattr(outer_curve, "is_a"):
    return None
  if not outer_curve.is_a("IfcPolyline"):
    return None

  points: list[tuple[float, float]] = []
  for point in list(getattr(outer_curve, "Points", []) or []):
    coords = _safe_point_coords(point, 2)
    if coords is None:
      continue
    points.append((_safe_float(coords[0]), _safe_float(coords[1])))

  if not points:
    return None

  xs = [point[0] for point in points]
  ys = [point[1] for point in points]
  return ((min(xs) + max(xs)) / 2.0, (min(ys) + max(ys)) / 2.0)


# Unwrap mapped or styled representation items to their underlying geometry.
def _unwrap_representation_item(item: Any) -> Any | None:
  current = item
  while current is not None and hasattr(current, "is_a") and current.is_a("IfcBooleanClippingResult"):
    current = getattr(current, "FirstOperand", None)
  return current


# Estimate the world anchor of a space from its geometry representation.
def _read_space_geometric_anchor_world(space: Any) -> Point3D | None:
  if space is None or not hasattr(space, "is_a") or not space.is_a("IfcSpace"):
    return None

  representation = getattr(space, "Representation", None)
  reps = list(getattr(representation, "Representations", []) or [])
  body_rep = None
  for rep in reps:
    identifier = str(getattr(rep, "RepresentationIdentifier", "") or "").strip().lower()
    if identifier == "body":
      body_rep = rep
      break
  if body_rep is None and reps:
    body_rep = reps[0]
  if body_rep is None:
    return None

  for raw_item in list(getattr(body_rep, "Items", []) or []):
    item = _unwrap_representation_item(raw_item)
    if item is None or not hasattr(item, "is_a") or not item.is_a("IfcExtrudedAreaSolid"):
      continue

    anchor_xy = _read_profile_anchor_xy(getattr(item, "SweptArea", None))
    if anchor_xy is None:
      continue

    local_anchor = Point3D(x=anchor_xy[0], y=anchor_xy[1], z=0.0)
    item_matrix = _axis2placement3d_to_matrix(getattr(item, "Position", None))
    if item_matrix is not None:
      local_anchor = _transform_point_by_matrix(local_anchor, item_matrix)

    placement = getattr(space, "ObjectPlacement", None)
    if placement is None:
      return local_anchor
    try:
      placement_matrix = ifcopenshell.util.placement.get_local_placement(placement)
    except Exception:
      return local_anchor
    return _transform_point_by_matrix(local_anchor, placement_matrix)

  return None


# Create and cache IfcOpenShell geometry settings for world coordinates.
def _get_world_geom_settings() -> Any | None:
  global _WORLD_GEOM_SETTINGS
  if ifcopenshell_geom is None:
    return None
  if _WORLD_GEOM_SETTINGS is not None:
    return _WORLD_GEOM_SETTINGS

  try:
    settings = ifcopenshell_geom.settings()
  except Exception:
    return None

  for key in (
    "use-world-coords",
    getattr(settings, "USE_WORLD_COORDS", None),
  ):
    if key is None:
      continue
    try:
      settings.set(key, True)
      break
    except Exception:
      continue

  _WORLD_GEOM_SETTINGS = settings
  return settings


# Estimate the world anchor of a product from its geometry representation.
def _read_product_geometric_anchor_world(product: Any) -> Point3D | None:
  settings = _get_world_geom_settings()
  if settings is None or ifcopenshell_geom is None:
    return None

  try:
    shape = ifcopenshell_geom.create_shape(settings, product)
  except Exception:
    return None

  geometry = getattr(shape, "geometry", None)
  verts = getattr(geometry, "verts", None)
  if verts is None:
    return None

  try:
    values = [_safe_float(value) for value in verts]
  except Exception:
    return None

  if len(values) < 3:
    return None

  xs = values[0::3]
  ys = values[1::3]
  zs = values[2::3]
  if not xs or not ys or not zs:
    return None

  return Point3D(
    x=(min(xs) + max(xs)) / 2.0,
    y=(min(ys) + max(ys)) / 2.0,
    z=min(zs),
  )


# Resolve the final IFC world position for an added furniture item.
def _resolve_furniture_ifc_world_position(
  item: FurnitureItem,
  viewer_to_model_units: float,
  container: Any | None = None,
) -> Point3D:
  relative_position = _parse_space_relative_position_from_custom(item.custom)
  if (
    relative_position is not None
    and container is not None
    and hasattr(container, "is_a")
    and container.is_a("IfcSpace")
  ):
    container_world = (
      _read_space_geometric_anchor_world(container)
      or _read_product_geometric_anchor_world(container)
      or _read_product_world_position(container)
    )
    if container_world is not None:
      dx = _safe_float(relative_position.x) * viewer_to_model_units
      dy = _safe_float(relative_position.y) * viewer_to_model_units
      dz = _safe_float(relative_position.z) * viewer_to_model_units
      ifc_dx, ifc_dy, ifc_dz = _viewer_delta_to_ifc_world(dx, dy, dz)
      return Point3D(
        x=_safe_float(container_world.x) + ifc_dx,
        y=_safe_float(container_world.y) + ifc_dy,
        z=_safe_float(container_world.z) + ifc_dz,
      )

  ifc_position = _viewer_point_to_ifc_world(item.position, viewer_to_model_units)
  return ifc_position


# Convert viewer box scale into IFC box dimensions in model units.
def _viewer_scale_to_ifc_box_dims(
  scale: Point3D | None,
  viewer_to_model_units: float = 1.0,
) -> tuple[float, float, float]:
  resolved = scale or Point3D(x=1.0, y=1.0, z=1.0)
  # Viewer is X-right, Y-up, Z-depth. IFC box local axes here are X-right, Y-depth, Z-up.
  width = _safe_dim(resolved.x, 1.0) * viewer_to_model_units
  depth = _safe_dim(resolved.z, 1.0) * viewer_to_model_units
  height = _safe_dim(resolved.y, 1.0) * viewer_to_model_units
  return (width, depth, height)


# Convert viewer mesh vertices into IFC vertex coordinates.
def _viewer_positions_to_ifc_vertices(
  positions: list[float],
  viewer_to_model_units: float = 1.0,
) -> list[tuple[float, float, float]]:
  vertices: list[tuple[float, float, float]] = []
  for index in range(0, len(positions), 3):
    if index + 2 >= len(positions):
      break
    x = _safe_float(positions[index]) * viewer_to_model_units
    y = _safe_float(positions[index + 1]) * viewer_to_model_units
    z = _safe_float(positions[index + 2]) * viewer_to_model_units
    vertices.append(_viewer_delta_to_ifc_world(x, y, z))
  return vertices


# Rewrite a product placement so it sits at an absolute world position.
def _set_product_absolute_position(model: Any, product: Any, position: Point3D) -> None:
  current_placement = getattr(product, "ObjectPlacement", None)
  if current_placement is not None and current_placement.is_a("IfcLocalPlacement"):
    relative = getattr(current_placement, "RelativePlacement", None)
    location = getattr(relative, "Location", None) if relative is not None else None
    if relative is not None and location is not None:
      target = position
      parent_placement = getattr(current_placement, "PlacementRelTo", None)
      if parent_placement is not None:
        target = _world_point_to_local_relative(parent_placement, position)

      coordinates = list(getattr(location, "Coordinates", []) or [])
      if relative.is_a("IfcAxis2Placement3D"):
        while len(coordinates) < 3:
          coordinates.append(0.0)
        coordinates[0] = _safe_float(target.x, 0.0)
        coordinates[1] = _safe_float(target.y, 0.0)
        coordinates[2] = _safe_float(target.z, 0.0)
        location.Coordinates = tuple(coordinates[:3])
        return
      if relative.is_a("IfcAxis2Placement2D"):
        while len(coordinates) < 2:
          coordinates.append(0.0)
        coordinates[0] = _safe_float(target.x, 0.0)
        coordinates[1] = _safe_float(target.y, 0.0)
        location.Coordinates = tuple(coordinates[:2])
        return

  axis = None
  ref_direction = None
  if current_placement is not None and current_placement.is_a("IfcLocalPlacement"):
    relative = getattr(current_placement, "RelativePlacement", None)
    if relative is not None and relative.is_a("IfcAxis2Placement3D"):
      axis = getattr(relative, "Axis", None)
      ref_direction = getattr(relative, "RefDirection", None)
  product.ObjectPlacement = _create_absolute_local_placement(model, position, axis, ref_direction)


# Check whether an IFC product still owns child spatial or decomposition relations.
def _product_has_children(product: Any) -> bool:
  for rel in list(getattr(product, "IsDecomposedBy", []) or []):
    related = list(getattr(rel, "RelatedObjects", []) or [])
    if related:
      return True
  for rel in list(getattr(product, "IsNestedBy", []) or []):
    related = list(getattr(rel, "RelatedObjects", []) or [])
    if related:
      return True
  for rel in list(getattr(product, "ContainsElements", []) or []):
    related = list(getattr(rel, "RelatedElements", []) or [])
    if related:
      return True
  return False


# Return the numeric id of an IFC entity when available.
def _entity_id(entity: Any) -> int | None:
  try:
    return int(entity.id())
  except Exception:
    return None


# Remove a product id from one inverse relation list safely.
def _remove_from_relation_list(model: Any, rel: Any, attr_name: str, product_id: int) -> bool:
  related = list(getattr(rel, attr_name, []) or [])
  if not related:
    return False
  filtered = [item for item in related if _entity_id(item) != product_id]
  if len(filtered) == len(related):
    return False
  if filtered:
    setattr(rel, attr_name, filtered)
  else:
    model.remove(rel)
  return True


# Detach a product from inverse IFC relations before deleting it.
def _unlink_product_from_inverse_relations(model: Any, product: Any) -> None:
  product_id = _entity_id(product)
  if product_id is None:
    return

  inverses = list(model.get_inverse(product) or [])
  for inverse in inverses:
    if inverse is None or not hasattr(inverse, "is_a"):
      continue
    try:
      if inverse.is_a("IfcRelContainedInSpatialStructure"):
        _remove_from_relation_list(model, inverse, "RelatedElements", product_id)
        continue
      if hasattr(inverse, "RelatedObjects"):
        if _remove_from_relation_list(model, inverse, "RelatedObjects", product_id):
          continue
      # Direct pair relations must be removed entirely when one side is hidden.
      if (
        inverse.is_a("IfcRelConnects")
        or inverse.is_a("IfcRelVoidsElement")
        or inverse.is_a("IfcRelFillsElement")
        or inverse.is_a("IfcRelSpaceBoundary")
      ):
        model.remove(inverse)
    except Exception:
      continue


# Clear product representations so deleted elements no longer render.
def _hide_product_geometry(product: Any) -> None:
  # Keep entity/type/style graph intact, just strip visible geometry.
  if hasattr(product, "Representation"):
    product.Representation = None


# Delete a product only when it has no remaining dependent children.
def _delete_ifc_product_if_leaf(model: Any, product: Any, warnings: list[str]) -> bool:
  if _product_has_children(product):
    warnings.append(f"Skipping delete for #{product.id()}: element has child elements.")
    return False
  try:
    _unlink_product_from_inverse_relations(model, product)
    _hide_product_geometry(product)
    return True
  except Exception as exc:  # noqa: BLE001
    warnings.append(f"Failed to delete IFC element #{product.id()}: {exc}")
    return False


# Create the best fitting IFC measure wrapper for a raw custom field value.
def _create_ifc_measure_value(model: Any, raw_value: Any, preferred_ifc_type: str | None = None) -> Any:
  if preferred_ifc_type:
    type_name = preferred_ifc_type.strip()
    try:
      if type_name in {"IfcBoolean"}:
        coerced = _coerce_bool(raw_value)
        if coerced is None:
          coerced = str(raw_value).strip().lower() in {"1", "true", "yes", "y"}
        return model.create_entity(type_name, bool(coerced))
      if type_name in {"IfcInteger", "IfcCountMeasure"}:
        return model.create_entity(type_name, int(round(_safe_float(raw_value, 0.0))))
      if type_name in {"IfcReal", "IfcLengthMeasure", "IfcAreaMeasure", "IfcVolumeMeasure"}:
        return model.create_entity(type_name, _safe_float(raw_value, 0.0))
      if type_name in {"IfcLabel", "IfcText", "IfcIdentifier"}:
        return model.create_entity(type_name, "" if raw_value is None else str(raw_value))
      return model.create_entity(type_name, "" if raw_value is None else str(raw_value))
    except Exception:
      # Fallback below.
      pass
  return model.create_entity("IfcLabel", "" if raw_value is None else str(raw_value))


# Write one custom field either into a direct attribute or a PSET property.
def _apply_custom_field_to_product(
  model: Any,
  product: Any,
  key: str,
  value: Any,
  warnings: list[str],
) -> bool:
  if key in EDITABLE_DIRECT_ATTRIBUTES:
    if not hasattr(product, key):
      return False
    try:
      setattr(product, key, None if value is None or str(value) == "" else str(value))
      return True
    except Exception as exc:  # noqa: BLE001
      warnings.append(f"Failed to set attribute {key} on #{product.id()}: {exc}")
      return False

  match = PSET_PROP_KEY_PATTERN.match(key)
  if not match:
    return False

  prop_id = int(match.group(2))
  prop = _safe_by_id(model, prop_id)
  if prop is None or not prop.is_a("IfcPropertySingleValue"):
    warnings.append(f"Skipping metadata key {key} for #{product.id()}: target property not found.")
    return False

  preferred_type: str | None = None
  nominal = getattr(prop, "NominalValue", None)
  if nominal is not None and hasattr(nominal, "is_a"):
    preferred_type = nominal.is_a()

  try:
    prop.NominalValue = _create_ifc_measure_value(model, value, preferred_type)
    return True
  except Exception as exc:  # noqa: BLE001
    warnings.append(f"Failed to update pset value {key} on #{product.id()}: {exc}")
    return False


# Find the main geometric representation context used for new geometry.
def _find_representation_context(model: Any) -> Any | None:
  subcontexts = model.by_type("IfcGeometricRepresentationSubContext")
  for context in subcontexts:
    identifier = str(getattr(context, "ContextIdentifier", "") or "").strip().lower()
    if identifier == "body":
      return context
  for context in subcontexts:
    context_type = str(getattr(context, "ContextType", "") or "").strip().lower()
    if context_type == "model":
      return context

  contexts = model.by_type("IfcGeometricRepresentationContext")
  for context in contexts:
    context_type = str(getattr(context, "ContextType", "") or "").strip().lower()
    if context_type == "model":
      return context

  if subcontexts:
    return subcontexts[0]
  if contexts:
    return contexts[0]
  return None


# Create a simple box representation for fallback furniture geometry.
def _create_box_representation(
  model: Any,
  context: Any,
  item: FurnitureItem,
  viewer_to_model_units: float,
) -> Any:
  width, depth, height = _viewer_scale_to_ifc_box_dims(item.scale, viewer_to_model_units)

  profile_location = model.createIfcCartesianPoint((0.0, 0.0))
  profile_position = model.createIfcAxis2Placement2D(profile_location, None)
  profile = model.createIfcRectangleProfileDef("AREA", None, profile_position, width, depth)

  # IfcRectangleProfileDef is already centered on its 2D placement.
  # Only shift vertically so the box remains centered on product placement.
  solid_origin = model.createIfcCartesianPoint((0.0, 0.0, -height / 2.0))
  solid_position = model.createIfcAxis2Placement3D(solid_origin, None, None)
  extrude_direction = model.createIfcDirection((0.0, 0.0, 1.0))
  solid = model.createIfcExtrudedAreaSolid(profile, solid_position, extrude_direction, height)

  shape = model.createIfcShapeRepresentation(context, "Body", "SweptSolid", [solid])
  return model.createIfcProductDefinitionShape(None, None, [shape])


# Create a triangulated mesh representation for custom furniture geometry.
def _create_mesh_representation(
  model: Any,
  context: Any,
  geometry: FurnitureGeometry,
  viewer_to_model_units: float,
) -> Any | None:
  if not geometry.positions or not geometry.indices:
    return None

  schema = str(getattr(model, "schema", "") or "").upper()
  if schema.startswith("IFC2X3"):
    return None

  vertices = _viewer_positions_to_ifc_vertices(geometry.positions, viewer_to_model_units)
  if len(vertices) < 3:
    return None

  faces: list[tuple[int, int, int]] = []
  for index in range(0, len(geometry.indices), 3):
    if index + 2 >= len(geometry.indices):
      break
    a = int(geometry.indices[index])
    b = int(geometry.indices[index + 1])
    c = int(geometry.indices[index + 2])
    if a < 0 or b < 0 or c < 0:
      continue
    if a >= len(vertices) or b >= len(vertices) or c >= len(vertices):
      continue
    faces.append((a + 1, b + 1, c + 1))

  if not faces:
    return None

  point_list = model.create_entity(
    "IfcCartesianPointList3D",
    CoordList=vertices,
  )
  face_set = model.create_entity(
    "IfcTriangulatedFaceSet",
    Coordinates=point_list,
    CoordIndex=faces,
    Closed=False,
  )
  shape = model.createIfcShapeRepresentation(context, "Body", "Tessellation", [face_set])
  return model.createIfcProductDefinitionShape(None, None, [shape])


# Normalize room numbers for stable matching across viewer and IFC data.
def _normalize_room_number(value: Any) -> str:
  return str(value or "").strip().lower()


# Check whether a space carries the requested normalized room number.
def _space_matches_room_number(space: Any, room_number: str) -> bool:
  expected = _normalize_room_number(room_number)
  if not expected:
    return False

  for attr in ("Name", "LongName", "Tag"):
    raw = getattr(space, attr, None)
    if _normalize_room_number(raw) == expected:
      return True

  psets = ifcopenshell.util.element.get_psets(space)
  for pset_values in psets.values():
    if not isinstance(pset_values, dict):
      continue
    for key, raw in pset_values.items():
      if key == "id":
        continue
      normalized_key = str(key).strip().lower().replace(" ", "").replace("_", "").replace("-", "")
      if normalized_key not in ROOM_NUMBER_KEYS:
        continue
      if _normalize_room_number(raw) == expected:
        return True
  return False


# Find the best spatial container for an added furniture item.
def _resolve_furniture_container(model: Any, item: FurnitureItem, warnings: list[str]) -> Any | None:
  if item.spaceIfcId is not None:
    target = _safe_by_id(model, item.spaceIfcId)
    if target is not None and hasattr(target, "is_a") and target.is_a("IfcSpace"):
      return target
    warnings.append(f'Furniture "{item.id}" space #{item.spaceIfcId} not found, using fallback container.')

  if item.roomNumber:
    for space in model.by_type("IfcSpace"):
      if _space_matches_room_number(space, item.roomNumber):
        return space
    warnings.append(f'Furniture "{item.id}" room "{item.roomNumber}" not found, using fallback container.')

  for ifc_type in ("IfcBuildingStorey", "IfcBuilding", "IfcSite", "IfcProject"):
    entities = model.by_type(ifc_type)
    if entities:
      return entities[0]
  return None


# Attach a product to a room or other spatial structure relation.
def _assign_product_to_spatial_structure(model: Any, product: Any, structure: Any) -> None:
  for rel in list(getattr(product, "ContainedInStructure", []) or []):
    if not rel.is_a("IfcRelContainedInSpatialStructure"):
      continue
    related = [entity for entity in list(getattr(rel, "RelatedElements", []) or []) if entity.id() != product.id()]
    if related:
      rel.RelatedElements = related
    else:
      model.remove(rel)

  target_rel = None
  for rel in list(getattr(structure, "ContainsElements", []) or []):
    if rel.is_a("IfcRelContainedInSpatialStructure"):
      target_rel = rel
      break

  if target_rel is not None:
    related = list(getattr(target_rel, "RelatedElements", []) or [])
    if all(entity.id() != product.id() for entity in related):
      related.append(product)
      target_rel.RelatedElements = related
    return

  model.create_entity(
    "IfcRelContainedInSpatialStructure",
    GlobalId=ifcopenshell.guid.new(),
    OwnerHistory=_first_owner_history(model),
    Name=None,
    Description=None,
    RelatedElements=[product],
    RelatingStructure=structure,
  )


# Create and insert one added object as a new IFC furnishing element.
def _add_furniture_as_proxy(
  model: Any,
  context: Any | None,
  item: FurnitureItem,
  viewer_to_model_units: float,
  warnings: list[str],
) -> bool:
  if context is None:
    warnings.append(f'Cannot add furniture "{item.id}": no IFC representation context found.')
    return False

  container = _resolve_furniture_container(model, item, warnings)
  owner_history = _first_owner_history(model)
  axis_values, ref_values = _build_furniture_axis_basis(item.rotation)
  world_position = _resolve_furniture_ifc_world_position(item, viewer_to_model_units, container)
  placement_rel_to = None
  placement_position = world_position
  container_placement = getattr(container, "ObjectPlacement", None) if container is not None else None
  if container_placement is not None and getattr(container_placement, "is_a", lambda *_: False)("IfcLocalPlacement"):
    placement_rel_to = container_placement
    placement_position = _world_point_to_local_relative(container_placement, world_position)
    axis_values = _normalize_direction(
      _world_vector_to_local_relative(container_placement, axis_values),
      axis_values,
    )
    ref_values = _normalize_direction(
      _world_vector_to_local_relative(container_placement, ref_values),
      ref_values,
    )
    ref_proj = _dot3(ref_values, axis_values)
    ref_values = _normalize_direction(
      (
        ref_values[0] - axis_values[0] * ref_proj,
        ref_values[1] - axis_values[1] * ref_proj,
        ref_values[2] - axis_values[2] * ref_proj,
      ),
      (1.0, 0.0, 0.0),
    )

  axis = model.createIfcDirection(axis_values)
  ref_direction = model.createIfcDirection(ref_values)

  placement = _create_absolute_local_placement(
    model,
    placement_position,
    axis,
    ref_direction,
    placement_rel_to,
  )

  representation = (
    _create_mesh_representation(model, context, item.geometry, viewer_to_model_units)
    if item.geometry is not None
    else None
  )
  if representation is None:
    representation = _create_box_representation(model, context, item, viewer_to_model_units)
  proxy = model.create_entity(
    "IfcFurnishingElement",
    GlobalId=ifcopenshell.guid.new(),
    OwnerHistory=owner_history,
    Name=item.name or item.id or "Furniture",
    Description=None,
    ObjectType=item.model or "Furniture",
    ObjectPlacement=placement,
    Representation=representation,
    Tag=item.id or None,
  )

  try:
    if hasattr(proxy, "PredefinedType"):
      proxy.PredefinedType = "NOTDEFINED"
  except Exception:
    # Best effort; schema differences can reject this assignment.
    pass

  if container is None:
    warnings.append(f'Furniture "{item.id}" was created without spatial container.')
    return True

  try:
    _assign_product_to_spatial_structure(model, proxy, container)
  except Exception as exc:  # noqa: BLE001
    warnings.append(f'Furniture "{item.id}" container assignment failed: {exc}')

  safe_custom = {
    str(key): value
    for key, value in (item.custom or {}).items()
    if not str(key).startswith("__baka")
  }
  if safe_custom:
    try:
      pset = _get_or_create_pset(model, proxy, "Pset_Baka_FurnitureItem")
      ifcopenshell.api.run("pset.edit_pset", model, pset=pset, properties=safe_custom)
    except Exception as exc:  # noqa: BLE001
      warnings.append(f'Furniture "{item.id}" metadata export failed: {exc}')
  return True


# Apply custom metadata edits stored in the metadata payload.
def _apply_metadata_custom_updates(model: Any, product: Any, custom: dict[str, Any], warnings: list[str]) -> int:
  updates = 0
  for key, value in custom.items():
    if key in {
      MOVE_DELTA_CUSTOM_KEY,
      ROTATE_DELTA_CUSTOM_KEY,
      INVERSE_COORDINATION_MATRIX_CUSTOM_KEY,
      PLACEMENT_POSITION_CUSTOM_KEY,
      SPACE_RELATIVE_POSITION_CUSTOM_KEY,
    }:
      continue
    if _apply_custom_field_to_product(model, product, key, value, warnings):
      updates += 1
  return updates


# Read a move delta encoded in metadata custom JSON.
def _parse_move_delta_from_custom(custom: dict[str, Any] | None) -> tuple[float, float, float] | None:
  if not custom:
    return None
  raw = custom.get(MOVE_DELTA_CUSTOM_KEY)
  if not isinstance(raw, str) or not raw.strip():
    return None
  try:
    parsed = json.loads(raw)
  except json.JSONDecodeError:
    return None
  if not isinstance(parsed, dict):
    return None
  return (
    _safe_float(parsed.get("dx"), 0.0),
    _safe_float(parsed.get("dy"), 0.0),
    _safe_float(parsed.get("dz"), 0.0),
  )


# Convert a move delta point into a numeric tuple.
def _parse_move_delta_from_point(point: Point3D | None) -> tuple[float, float, float] | None:
  if point is None:
    return None
  return (
    _safe_float(point.x, 0.0),
    _safe_float(point.y, 0.0),
    _safe_float(point.z, 0.0),
  )


# Read a rotation delta encoded in metadata custom JSON.
def _parse_rotate_delta_from_custom(custom: dict[str, Any] | None) -> tuple[float, float, float] | None:
  if not custom:
    return None
  raw = custom.get(ROTATE_DELTA_CUSTOM_KEY)
  if not isinstance(raw, str) or not raw.strip():
    return None
  try:
    parsed = json.loads(raw)
  except json.JSONDecodeError:
    return None
  if not isinstance(parsed, dict):
    return None
  return (
    _safe_float(parsed.get("x"), 0.0),
    _safe_float(parsed.get("y"), 0.0),
    _safe_float(parsed.get("z"), 0.0),
  )


# Convert a rotation delta point into a numeric tuple.
def _parse_rotate_delta_from_point(point: Point3D | None) -> tuple[float, float, float] | None:
  if point is None:
    return None
  return (
    _safe_float(point.x, 0.0),
    _safe_float(point.y, 0.0),
    _safe_float(point.z, 0.0),
  )


# Read an absolute placement position encoded in metadata custom JSON.
def _parse_placement_position_from_custom(custom: dict[str, Any] | None) -> Point3D | None:
  if not custom:
    return None
  raw = custom.get(PLACEMENT_POSITION_CUSTOM_KEY)
  if not isinstance(raw, str) or not raw.strip():
    return None
  return _parse_point_json(raw)


# Read a room-relative position encoded in furniture custom JSON.
def _parse_space_relative_position_from_custom(custom: dict[str, Any] | None) -> Point3D | None:
  if not custom:
    return None
  raw = custom.get(SPACE_RELATIVE_POSITION_CUSTOM_KEY)
  if not isinstance(raw, str) or not raw.strip():
    return None
  return _parse_point_json(raw)


# Resolve the target IFC world position for a changed original element.
def _resolve_metadata_ifc_world_position(
  entry: MetadataEntry,
  viewer_to_model_units: float,
) -> Point3D | None:
  viewer_position = _parse_placement_position_from_custom(entry.custom)
  if viewer_position is None:
    return None

  return _viewer_point_to_ifc_world(viewer_position, viewer_to_model_units)


# Parse a generic JSON point payload into Point3D.
def _parse_point_json(raw: Any) -> Point3D | None:
  if isinstance(raw, Point3D):
    return raw
  if not isinstance(raw, str) or not raw.strip():
    return None
  try:
    parsed = json.loads(raw)
  except json.JSONDecodeError:
    return None
  if not isinstance(parsed, dict):
    return None
  try:
    return Point3D(
      x=_safe_float(parsed.get("x"), 0.0),
      y=_safe_float(parsed.get("y"), 0.0),
      z=_safe_float(parsed.get("z"), 0.0),
    )
  except Exception:
    return None


# Normalize a 3D vector and fall back when its length is invalid.
def _normalize_direction(
  values: tuple[float, float, float],
  fallback: tuple[float, float, float],
) -> tuple[float, float, float]:
  x, y, z = values
  length = math.sqrt(x * x + y * y + z * z)
  if length <= 1e-12:
    return fallback
  return (x / length, y / length, z / length)


# Return the dot product of two 3D vectors.
def _dot3(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


# Return the cross product of two 3D vectors.
def _cross3(
  a: tuple[float, float, float],
  b: tuple[float, float, float],
) -> tuple[float, float, float]:
  return (
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  )


# Rotate a 3D vector by Euler angles in XYZ order.
def _rotate_vector_xyz(
  vector: tuple[float, float, float],
  rx: float,
  ry: float,
  rz: float,
) -> tuple[float, float, float]:
  x, y, z = vector

  if abs(rx) > 1e-12:
    cx = math.cos(rx)
    sx = math.sin(rx)
    y, z = y * cx - z * sx, y * sx + z * cx
  if abs(ry) > 1e-12:
    cy = math.cos(ry)
    sy = math.sin(ry)
    x, z = x * cy + z * sy, -x * sy + z * cy
  if abs(rz) > 1e-12:
    cz = math.cos(rz)
    sz = math.sin(rz)
    x, y = x * cz - y * sz, x * sz + y * cz

  return (x, y, z)


# Read the last baked absolute position stored on a product, if any.
def _read_previous_exported_position(product: Any) -> Point3D | None:
  pset_values = _read_pset_values(product, ELEMENT_STATE_PSET)
  if not pset_values:
    return None
  return _parse_point_json(pset_values.get("PositionJson"))


# Read the current product world position from its placement chain.
def _read_product_world_position(product: Any) -> Point3D | None:
  placement = getattr(product, "ObjectPlacement", None)
  if placement is None:
    return None
  try:
    matrix = ifcopenshell.util.placement.get_local_placement(placement)
  except Exception:
    return None
  if matrix is None:
    return None
  try:
    return Point3D(
      x=float(matrix[0][3]),
      y=float(matrix[1][3]),
      z=float(matrix[2][3]),
    )
  except Exception:
    return None


# Subtract one 3D point from another.
def _subtract_points(a: Point3D, b: Point3D) -> Point3D:
  return Point3D(
    x=_safe_float(a.x) - _safe_float(b.x),
    y=_safe_float(a.y) - _safe_float(b.y),
    z=_safe_float(a.z) - _safe_float(b.z),
  )


# Infer the shared viewer-to-IFC origin offset from stored metadata.
def _infer_viewer_model_origin_offset(
  model: Any,
  metadata: list[MetadataEntry],
  viewer_to_model_units: float,
) -> Point3D | None:
  offsets: list[Point3D] = []
  for entry in metadata:
    if entry.deleted:
      continue
    viewer_current = _parse_placement_position_from_custom(entry.custom)
    if viewer_current is None:
      continue
    product = _safe_by_id(model, entry.ifcId)
    if product is None or not product.is_a("IfcProduct"):
      continue
    source_world = _read_product_world_position(product)
    if source_world is None:
      continue

    move_delta = _parse_move_delta_from_point(entry.moveDelta)
    if move_delta is None:
      move_delta = _parse_move_delta_from_custom(entry.custom)
    if move_delta is None:
      move_delta = (0.0, 0.0, 0.0)

    viewer_base = Point3D(
      x=_safe_float(viewer_current.x) - _safe_float(move_delta[0]),
      y=_safe_float(viewer_current.y) - _safe_float(move_delta[1]),
      z=_safe_float(viewer_current.z) - _safe_float(move_delta[2]),
    )
    viewer_base_ifc = _viewer_point_to_ifc_world(viewer_base, viewer_to_model_units)
    offsets.append(_subtract_points(source_world, viewer_base_ifc))

  if not offsets:
    return None

  count = float(len(offsets))
  return Point3D(
    x=sum(_safe_float(item.x) for item in offsets) / count,
    y=sum(_safe_float(item.y) for item in offsets) / count,
    z=sum(_safe_float(item.z) for item in offsets) / count,
  )


# Read the rotation matrix of a placement parent for local delta conversion.
def _placement_parent_rotation_matrix(placement: Any) -> list[list[float]] | None:
  parent = getattr(placement, "PlacementRelTo", None)
  if parent is None:
    return None
  try:
    matrix = ifcopenshell.util.placement.get_local_placement(parent)
  except Exception:
    return None
  if matrix is None:
    return None
  try:
    return [
      [float(matrix[0][0]), float(matrix[0][1]), float(matrix[0][2])],
      [float(matrix[1][0]), float(matrix[1][1]), float(matrix[1][2])],
      [float(matrix[2][0]), float(matrix[2][1]), float(matrix[2][2])],
    ]
  except Exception:
    return None


# Convert a world-space delta into the local frame of a placement.
def _world_delta_to_local(placement: Any, dx: float, dy: float, dz: float) -> tuple[float, float, float]:
  rotation = _placement_parent_rotation_matrix(placement)
  if rotation is None:
    return (dx, dy, dz)
  # local = R^T * world
  return (
    rotation[0][0] * dx + rotation[1][0] * dy + rotation[2][0] * dz,
    rotation[0][1] * dx + rotation[1][1] * dy + rotation[2][1] * dz,
    rotation[0][2] * dx + rotation[1][2] * dy + rotation[2][2] * dz,
  )


# Translate one local placement by a local-space delta.
def _translate_local_placement_by_delta(placement: Any, dx: float, dy: float, dz: float) -> bool:
  if placement is None or not placement.is_a("IfcLocalPlacement"):
    return False
  relative = getattr(placement, "RelativePlacement", None)
  if relative is None:
    return False
  location = getattr(relative, "Location", None)
  if location is None:
    return False
  coordinates = list(getattr(location, "Coordinates", []) or [])
  if len(coordinates) < 2:
    return False

  local_dx, local_dy, local_dz = _world_delta_to_local(placement, dx, dy, dz)
  coordinates[0] = _safe_float(coordinates[0], 0.0) + local_dx
  coordinates[1] = _safe_float(coordinates[1], 0.0) + local_dy
  if relative.is_a("IfcAxis2Placement3D"):
    if len(coordinates) >= 3:
      coordinates[2] = _safe_float(coordinates[2], 0.0) + local_dz
    else:
      coordinates.append(local_dz)
  elif relative.is_a("IfcAxis2Placement2D"):
    # 2D placements ignore Z translation.
    coordinates = coordinates[:2]
  else:
    return False
  location.Coordinates = tuple(coordinates)
  return True


# Translate a product placement by a world-space delta.
def _translate_product_by_delta(product: Any, dx: float, dy: float, dz: float) -> bool:
  placement = getattr(product, "ObjectPlacement", None)
  return _translate_local_placement_by_delta(placement, dx, dy, dz)


# Shift top-level product placements by one shared model offset.
def _shift_root_product_placements(model: Any, dx: float, dy: float, dz: float) -> int:
  shifted = 0
  seen_placements: set[int] = set()
  for product in model.by_type("IfcProduct"):
    placement = getattr(product, "ObjectPlacement", None)
    if placement is None or not placement.is_a("IfcLocalPlacement"):
      continue
    parent = getattr(placement, "PlacementRelTo", None)
    if parent is not None:
      continue
    placement_id = _entity_id(placement)
    if placement_id is not None and placement_id in seen_placements:
      continue
    if _translate_local_placement_by_delta(placement, dx, dy, dz):
      shifted += 1
      if placement_id is not None:
        seen_placements.add(placement_id)
  return shifted


# Convert a viewer delta vector into IFC world-axis order.
def _viewer_delta_to_ifc_world(dx: float, dy: float, dz: float) -> tuple[float, float, float]:
  # Viewer uses Y-up coordinates while IFC world is Z-up in this pipeline.
  # Keep X, map viewer Y->IFC Z, and invert viewer Z when mapping to IFC Y.
  return (dx, -dz, dy)


# Convert viewer Euler rotation into IFC world-axis order.
def _viewer_rotation_to_ifc_world(rx: float, ry: float, rz: float) -> tuple[float, float, float]:
  # Viewer rotation deltas are stored in viewer axes (X right, Y up, Z forward).
  # IFC world in this pipeline is X right, Y depth, Z up.
  # Mapping follows the same basis used for translation conversion.
  return (rx, -rz, ry)


# Rotate a product placement by a delta expressed in viewer coordinates.
def _rotate_product_by_delta(model: Any, product: Any, rx: float, ry: float, rz: float) -> bool:
  placement = getattr(product, "ObjectPlacement", None)
  if placement is None or not placement.is_a("IfcLocalPlacement"):
    return False
  relative = getattr(placement, "RelativePlacement", None)
  if relative is None:
    return False

  if relative.is_a("IfcAxis2Placement3D"):
    axis_dir = getattr(relative, "Axis", None)
    ref_dir = getattr(relative, "RefDirection", None)
    axis_values = tuple(getattr(axis_dir, "DirectionRatios", (0.0, 0.0, 1.0)) or (0.0, 0.0, 1.0))
    ref_values = tuple(getattr(ref_dir, "DirectionRatios", (1.0, 0.0, 0.0)) or (1.0, 0.0, 0.0))

    axis = _normalize_direction(
      (
        _safe_float(axis_values[0], 0.0),
        _safe_float(axis_values[1], 0.0),
        _safe_float(axis_values[2], 1.0),
      ),
      (0.0, 0.0, 1.0),
    )
    ref = _normalize_direction(
      (
        _safe_float(ref_values[0], 1.0),
        _safe_float(ref_values[1], 0.0),
        _safe_float(ref_values[2], 0.0),
      ),
      (1.0, 0.0, 0.0),
    )

    # Keep orthonormal basis before rotating.
    ref_proj = _dot3(ref, axis)
    ref = _normalize_direction(
      (
        ref[0] - axis[0] * ref_proj,
        ref[1] - axis[1] * ref_proj,
        ref[2] - axis[2] * ref_proj,
      ),
      (1.0, 0.0, 0.0),
    )
    axis = _normalize_direction(_cross3(ref, _cross3(axis, ref)), axis)

    rotated_axis = _normalize_direction(_rotate_vector_xyz(axis, rx, ry, rz), axis)
    rotated_ref = _normalize_direction(_rotate_vector_xyz(ref, rx, ry, rz), ref)

    # Re-orthogonalize reference against new axis for valid Axis2Placement3D basis.
    rotated_ref_proj = _dot3(rotated_ref, rotated_axis)
    rotated_ref = _normalize_direction(
      (
        rotated_ref[0] - rotated_axis[0] * rotated_ref_proj,
        rotated_ref[1] - rotated_axis[1] * rotated_ref_proj,
        rotated_ref[2] - rotated_axis[2] * rotated_ref_proj,
      ),
      (1.0, 0.0, 0.0),
    )

    relative.Axis = model.createIfcDirection(rotated_axis)
    relative.RefDirection = model.createIfcDirection(rotated_ref)
    return True

  if relative.is_a("IfcAxis2Placement2D"):
    ref_dir = getattr(relative, "RefDirection", None)
    ref_values = tuple(getattr(ref_dir, "DirectionRatios", (1.0, 0.0)) or (1.0, 0.0))
    x = _safe_float(ref_values[0], 1.0)
    y = _safe_float(ref_values[1], 0.0)
    if abs(x) <= 1e-12 and abs(y) <= 1e-12:
      x, y = 1.0, 0.0
    angle = rz
    ca = math.cos(angle)
    sa = math.sin(angle)
    nx, ny = x * ca - y * sa, x * sa + y * ca
    relative.RefDirection = model.createIfcDirection((nx, ny))
    return True

  return False


# Run the full export pipeline and write a new IFC with all pending changes.
def _export_state(request: ExportStateRequest) -> ExportStateResponse:
  source_path = _resolve_existing_path(request.source_ifc_path, "source_ifc_path")
  target_path = _resolve_target_path(request.target_ifc_path)
  warnings: list[str] = []

  if source_path == target_path:
    raise ValueError("source_ifc_path and target_ifc_path must be different files")

  shutil.copy2(source_path, target_path)

  try:
    model = ifcopenshell.open(str(target_path))
  except Exception as exc:  # noqa: BLE001
    raise RuntimeError(f"Failed to open copied IFC file: {target_path}") from exc

  # Viewer works in meters; IFC placements use project length units (often millimeters).
  # Convert viewer deltas to model units before writing ObjectPlacement coordinates.
  meters_per_unit = _meters_per_model_unit(model)
  viewer_to_model_units = 1.0 / meters_per_unit if meters_per_unit > 0 else 1.0
  viewer_model_origin_offset = _infer_viewer_model_origin_offset(
    model, request.metadata, viewer_to_model_units
  )

  hard_deleted = 0
  hard_moved = 0
  hard_rotated = 0
  hard_metadata_updates = 0
  hard_added = 0
  purged_state_psets = _purge_editor_state_psets(model)

  # 1) Shift the source model into the same global frame as the viewer.
  if viewer_model_origin_offset is not None:
    shift_dx = -_safe_float(viewer_model_origin_offset.x)
    shift_dy = -_safe_float(viewer_model_origin_offset.y)
    shift_dz = -_safe_float(viewer_model_origin_offset.z)
    if abs(shift_dx) > 1e-9 or abs(shift_dy) > 1e-9 or abs(shift_dz) > 1e-9:
      shifted_roots = _shift_root_product_placements(model, shift_dx, shift_dy, shift_dz)
      if shifted_roots == 0:
        warnings.append("Viewer-state export could not shift any root IFC placements.")

  # 2) Hard delete only leaf IFC products.
  for entry in request.metadata:
    if not entry.deleted:
      continue
    product = _safe_by_id(model, entry.ifcId)
    if product is None:
      warnings.append(f"Delete references missing ifcId {entry.ifcId}")
      continue
    if not product.is_a("IfcProduct"):
      warnings.append(f"Delete skipped for ifcId {entry.ifcId}: entity is not IfcProduct.")
      continue
    if _delete_ifc_product_if_leaf(model, product, warnings):
      hard_deleted += 1

  # 3) Apply placement and metadata updates on remaining IFC products.
  for entry in request.metadata:
    if entry.deleted:
      continue
    product = _safe_by_id(model, entry.ifcId)
    if product is None:
      warnings.append(f"Metadata references missing ifcId {entry.ifcId}")
      continue
    if not product.is_a("IfcProduct"):
      warnings.append(f"Metadata skipped for ifcId {entry.ifcId}: entity is not IfcProduct.")
      continue

    if entry.position is not None:
      try:
        absolute_position = _resolve_metadata_ifc_world_position(entry, viewer_to_model_units)
        if absolute_position is not None:
          _set_product_absolute_position(model, product, absolute_position)
          hard_moved += 1
        else:
          move_delta = _parse_move_delta_from_point(entry.moveDelta)
          if move_delta is None:
            move_delta = _parse_move_delta_from_custom(entry.custom)
          if move_delta is None:
            previous_position = _read_previous_exported_position(product)
            if previous_position is not None:
              move_delta = (
                _safe_float(entry.position.x) - _safe_float(previous_position.x),
                _safe_float(entry.position.y) - _safe_float(previous_position.y),
                _safe_float(entry.position.z) - _safe_float(previous_position.z),
              )

          if move_delta is not None:
            dx, dy, dz = move_delta
            dx, dy, dz = _viewer_delta_to_ifc_world(dx, dy, dz)
            dx *= viewer_to_model_units
            dy *= viewer_to_model_units
            dz *= viewer_to_model_units
            if (
              abs(dx) > 1e-9
              or abs(dy) > 1e-9
              or abs(dz) > 1e-9
            ):
              if _translate_product_by_delta(product, dx, dy, dz):
                hard_moved += 1
              else:
                warnings.append(
                  f"Failed to move IFC element #{entry.ifcId}: unsupported ObjectPlacement."
                )
          else:
            warnings.append(
              f"Skipping absolute move for #{entry.ifcId}: missing movement delta baseline."
            )
      except Exception as exc:  # noqa: BLE001
        warnings.append(f"Failed to move IFC element #{entry.ifcId}: {exc}")

    if entry.rotation is not None or entry.rotateDelta is not None:
      try:
        rotate_delta = _parse_rotate_delta_from_point(entry.rotateDelta)
        if rotate_delta is None:
          rotate_delta = _parse_rotate_delta_from_point(entry.rotation)
        if rotate_delta is None:
          rotate_delta = _parse_rotate_delta_from_custom(entry.custom)

        if rotate_delta is not None:
          rx, ry, rz = rotate_delta
          rx, ry, rz = _viewer_rotation_to_ifc_world(rx, ry, rz)
          if abs(rx) > 1e-9 or abs(ry) > 1e-9 or abs(rz) > 1e-9:
            if _rotate_product_by_delta(model, product, rx, ry, rz):
              hard_rotated += 1
            else:
              warnings.append(
                f"Failed to rotate IFC element #{entry.ifcId}: unsupported ObjectPlacement."
              )
      except Exception as exc:  # noqa: BLE001
        warnings.append(f"Failed to rotate IFC element #{entry.ifcId}: {exc}")

    if entry.custom:
      hard_metadata_updates += _apply_metadata_custom_updates(model, product, entry.custom, warnings)

  # 4) Add custom furniture as IfcFurnishingElement.
  context = _find_representation_context(model)
  for item in request.furniture:
    try:
      if _add_furniture_as_proxy(model, context, item, viewer_to_model_units, warnings):
        hard_added += 1
    except Exception as exc:  # noqa: BLE001
      warnings.append(f'Failed to add furniture "{item.id}" as IFC proxy: {exc}')

  exported_metadata = 0
  if purged_state_psets > 0:
    warnings.append(f"Removed {purged_state_psets} editor-state Psets from exported IFC.")

  if hard_deleted or hard_moved or hard_rotated or hard_metadata_updates or hard_added:
    warnings.append(
      "Hard IFC apply summary: "
      + f"deleted={hard_deleted}, moved={hard_moved}, "
      + f"rotated={hard_rotated}, metadataUpdates={hard_metadata_updates}, added={hard_added}"
    )

  try:
    model.write(str(target_path))
  except Exception as exc:  # noqa: BLE001
    raise RuntimeError(f"Failed to write IFC output: {target_path}") from exc

  return ExportStateResponse(
    target_ifc_path=str(target_path),
    exported_metadata_count=exported_metadata,
    exported_furniture_count=len(request.furniture),
    exported_history_count=len(request.history),
    warnings=warnings,
  )


@app.get("/health")
# Return a simple health response for the service.
def health() -> dict[str, str]:
  return {"status": "ok"}


@app.post("/state/import", response_model=ImportStateResponse)
# Handle the public import endpoint and wrap validation errors as HTTP responses.
def import_state(payload: ImportStateRequest) -> ImportStateResponse:
  try:
    return _import_state(payload)
  except ValueError as exc:
    raise HTTPException(status_code=400, detail=str(exc)) from exc
  except RuntimeError as exc:
    raise HTTPException(status_code=422, detail=str(exc)) from exc
  except Exception as exc:  # noqa: BLE001
    logger.exception("Unexpected import failure")
    raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/state/export", response_model=ExportStateResponse)
# Handle the public export endpoint and wrap validation errors as HTTP responses.
def export_state(payload: ExportStateRequest) -> ExportStateResponse:
  try:
    return _export_state(payload)
  except ValueError as exc:
    raise HTTPException(status_code=400, detail=str(exc)) from exc
  except RuntimeError as exc:
    raise HTTPException(status_code=422, detail=str(exc)) from exc
  except Exception as exc:  # noqa: BLE001
    logger.exception("Unexpected export failure")
    raise HTTPException(status_code=500, detail=str(exc)) from exc

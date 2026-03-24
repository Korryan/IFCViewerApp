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
STATE_VERSION = "baka-ifc-state-v1"
PSET_PROP_KEY_PATTERN = re.compile(r"^pset-(\d+)-(\d+)$")
EDITABLE_DIRECT_ATTRIBUTES = {"Name", "Description", "ObjectType", "Tag", "LongName"}
ROOM_NUMBER_KEYS = {"roomnumber", "raumnummer", "number"}
MOVE_DELTA_CUSTOM_KEY = "__bakaMoveDeltaJson"
ROTATE_DELTA_CUSTOM_KEY = "__bakaRotateDeltaJson"

app = FastAPI(title="ifc-ops", version="0.1.0")


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


def _now_iso() -> str:
  return datetime.now(UTC).isoformat()


def _is_inside_data_root(path: Path) -> bool:
  try:
    path.resolve().relative_to(DATA_ROOT)
    return True
  except ValueError:
    return False


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


def _safe_by_id(model: Any, entity_id: Any) -> Any | None:
  try:
    if entity_id is None:
      return None
    return model.by_id(int(entity_id))
  except Exception:
    return None


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


def _try_json_point(raw: Any, warnings: list[str], field_name: str) -> Point3D | None:
  parsed = _try_json_dict(raw, warnings, field_name)
  if not parsed:
    return None
  try:
    return Point3D(x=float(parsed["x"]), y=float(parsed["y"]), z=float(parsed["z"]))
  except (KeyError, TypeError, ValueError):
    warnings.append(f"{field_name} JSON is not a valid point object")
    return None


def _try_json_list(raw: Any, warnings: list[str], field_name: str) -> list[Any]:
  if raw is None:
    return []
  if isinstance(raw, list):
    return raw
  if isinstance(raw, str):
    try:
      parsed = json.loads(raw)
    except json.JSONDecodeError:
      warnings.append(f"{field_name} JSON decode failed")
      return []
    if isinstance(parsed, list):
      return parsed
    warnings.append(f"{field_name} JSON is not a list")
    return []
  warnings.append(f"{field_name} is not a list")
  return []


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


def _read_pset_values(product: Any, pset_name: str) -> dict[str, Any] | None:
  psets = ifcopenshell.util.element.get_psets(product)
  values = psets.get(pset_name)
  if not isinstance(values, dict):
    return None
  return {key: value for key, value in values.items() if key != "id"}


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


def _first_ifc_project(model: Any) -> Any | None:
  projects = model.by_type("IfcProject")
  if not projects:
    return None
  return projects[0]


def _import_state(request: ImportStateRequest) -> ImportStateResponse:
  source_path = _resolve_existing_path(request.source_ifc_path, "source_ifc_path")
  warnings: list[str] = []

  try:
    model = ifcopenshell.open(str(source_path))
  except Exception as exc:  # noqa: BLE001
    raise RuntimeError(f"Failed to open IFC file: {source_path}") from exc

  metadata_entries: list[MetadataEntry] = []
  for product in model.by_type("IfcProduct"):
    pset_values = _read_pset_values(product, ELEMENT_STATE_PSET)
    if not pset_values:
      continue

    item: dict[str, Any] = {"ifcId": int(product.id())}
    raw_type = pset_values.get("Type")
    if isinstance(raw_type, str) and raw_type.strip():
      item["type"] = raw_type
    raw_updated = pset_values.get("UpdatedAt")
    if isinstance(raw_updated, str) and raw_updated.strip():
      item["updatedAt"] = raw_updated
    raw_deleted = pset_values.get("Deleted")
    coerced_deleted = _coerce_bool(raw_deleted)
    if coerced_deleted is not None:
      item["deleted"] = coerced_deleted

    custom = _try_json_dict(
      pset_values.get("CustomJson"),
      warnings,
      f"Metadata #{product.id()} CustomJson",
    )
    if custom is not None:
      item["custom"] = custom

    position = _try_json_point(
      pset_values.get("PositionJson"),
      warnings,
      f"Metadata #{product.id()} PositionJson",
    )
    if position is not None:
      item["position"] = position

    move_delta = _try_json_point(
      pset_values.get("MoveDeltaJson"),
      warnings,
      f"Metadata #{product.id()} MoveDeltaJson",
    )
    if move_delta is not None:
      item["moveDelta"] = move_delta

    rotation = _try_json_point(
      pset_values.get("RotationJson"),
      warnings,
      f"Metadata #{product.id()} RotationJson",
    )
    if rotation is not None:
      item["rotation"] = rotation

    rotate_delta = _try_json_point(
      pset_values.get("RotateDeltaJson"),
      warnings,
      f"Metadata #{product.id()} RotateDeltaJson",
    )
    if rotate_delta is not None:
      item["rotateDelta"] = rotate_delta

    try:
      metadata_entries.append(MetadataEntry.model_validate(item))
    except Exception as exc:  # noqa: BLE001
      warnings.append(f"Skipping invalid metadata item for #{product.id()}: {exc}")

  furniture_entries: list[FurnitureItem] = []
  history_entries: list[HistoryEntry] = []
  project = _first_ifc_project(model)
  if project is None:
    warnings.append("No IfcProject found in source IFC, project-level state was skipped.")
  else:
    furniture_pset = _read_pset_values(project, FURNITURE_STATE_PSET) or {}
    history_pset = _read_pset_values(project, HISTORY_STATE_PSET) or {}

    furniture_items = _try_json_list(
      furniture_pset.get("ItemsJson"),
      warnings,
      "Furniture ItemsJson",
    )
    for index, raw_item in enumerate(furniture_items):
      try:
        item = FurnitureItem.model_validate(raw_item)
        if _has_baked_furniture_proxy(model, item):
          continue
        furniture_entries.append(item)
      except Exception as exc:  # noqa: BLE001
        warnings.append(f"Skipping invalid furniture item at index {index}: {exc}")

    history_items = _try_json_list(
      history_pset.get("ItemsJson"),
      warnings,
      "History ItemsJson",
    )
    for index, raw_item in enumerate(history_items):
      try:
        history_entries.append(HistoryEntry.model_validate(raw_item))
      except Exception as exc:  # noqa: BLE001
        warnings.append(f"Skipping invalid history item at index {index}: {exc}")

  return ImportStateResponse(
    metadata=metadata_entries,
    furniture=furniture_entries,
    history=history_entries,
    warnings=warnings,
  )


def _dump_json(value: Any) -> str:
  return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _first_owner_history(model: Any) -> Any | None:
  owners = model.by_type("IfcOwnerHistory")
  return owners[0] if owners else None


def _safe_float(value: Any, default: float = 0.0) -> float:
  try:
    parsed = float(value)
    if math.isfinite(parsed):
      return parsed
  except (TypeError, ValueError):
    pass
  return default


def _meters_per_model_unit(model: Any) -> float:
  try:
    scale = float(ifcopenshell.util.unit.calculate_unit_scale(model))
    if math.isfinite(scale) and scale > 0:
      return scale
  except Exception:
    pass
  return 1.0


def _safe_dim(value: Any, default: float = 1.0) -> float:
  parsed = abs(_safe_float(value, default))
  if parsed <= 1e-6:
    return default
  return parsed


def _build_ref_direction(rotation: Point3D | None) -> tuple[float, float, float]:
  # Viewer uses Y-up coordinates, so horizontal yaw is stored on the viewer Y axis.
  # IFC placement here is Z-up, therefore the horizontal ref direction comes from viewer Y.
  yaw = _safe_float(rotation.y if rotation is not None else 0.0, 0.0)
  return (math.cos(yaw), math.sin(yaw), 0.0)


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


def _viewer_point_to_ifc_world(position: Point3D, viewer_to_model_units: float = 1.0) -> Point3D:
  x = _safe_float(position.x) * viewer_to_model_units
  y = _safe_float(position.y) * viewer_to_model_units
  z = _safe_float(position.z) * viewer_to_model_units
  ifc_x, ifc_y, ifc_z = _viewer_delta_to_ifc_world(x, y, z)
  return Point3D(x=ifc_x, y=ifc_y, z=ifc_z)


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


def _set_product_absolute_position(model: Any, product: Any, position: Point3D) -> None:
  current_placement = getattr(product, "ObjectPlacement", None)
  axis = None
  ref_direction = None
  if current_placement is not None and current_placement.is_a("IfcLocalPlacement"):
    relative = getattr(current_placement, "RelativePlacement", None)
    if relative is not None and relative.is_a("IfcAxis2Placement3D"):
      axis = getattr(relative, "Axis", None)
      ref_direction = getattr(relative, "RefDirection", None)
  product.ObjectPlacement = _create_absolute_local_placement(model, position, axis, ref_direction)


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


def _entity_id(entity: Any) -> int | None:
  try:
    return int(entity.id())
  except Exception:
    return None


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


def _hide_product_geometry(product: Any) -> None:
  # Keep entity/type/style graph intact, just strip visible geometry.
  if hasattr(product, "Representation"):
    product.Representation = None


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

  solid_origin = model.createIfcCartesianPoint((-width / 2.0, -depth / 2.0, -height / 2.0))
  solid_position = model.createIfcAxis2Placement3D(solid_origin, None, None)
  extrude_direction = model.createIfcDirection((0.0, 0.0, 1.0))
  solid = model.createIfcExtrudedAreaSolid(profile, solid_position, extrude_direction, height)

  shape = model.createIfcShapeRepresentation(context, "Body", "SweptSolid", [solid])
  return model.createIfcProductDefinitionShape(None, None, [shape])


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


def _normalize_room_number(value: Any) -> str:
  return str(value or "").strip().lower()


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


def _has_baked_furniture_proxy(model: Any, item: FurnitureItem) -> bool:
  item_id = str(item.id or "").strip()
  if not item_id:
    return False

  for ifc_type in ("IfcFurnishingElement", "IfcBuildingElementProxy"):
    for proxy in model.by_type(ifc_type):
      tag = str(getattr(proxy, "Tag", "") or "").strip()
      if tag == item_id:
        return True
  return False


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
  ref = _build_ref_direction(item.rotation)
  axis = model.createIfcDirection((0.0, 0.0, 1.0))
  ref_direction = model.createIfcDirection(ref)
  # Keep furnishing placement in project/world coordinates for better interoperability
  # across viewers. Spatial containment is handled separately by IfcRelContainedInSpatialStructure.
  placement = _create_absolute_local_placement(
    model,
    _viewer_point_to_ifc_world(item.position, viewer_to_model_units),
    axis,
    ref_direction,
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

  if item.custom:
    try:
      pset = _get_or_create_pset(model, proxy, "Pset_Baka_FurnitureItem")
      ifcopenshell.api.run("pset.edit_pset", model, pset=pset, properties=item.custom)
    except Exception as exc:  # noqa: BLE001
      warnings.append(f'Furniture "{item.id}" metadata export failed: {exc}')
  return True


def _apply_metadata_custom_updates(model: Any, product: Any, custom: dict[str, Any], warnings: list[str]) -> int:
  updates = 0
  for key, value in custom.items():
    if key in {MOVE_DELTA_CUSTOM_KEY, ROTATE_DELTA_CUSTOM_KEY}:
      continue
    if _apply_custom_field_to_product(model, product, key, value, warnings):
      updates += 1
  return updates


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


def _parse_move_delta_from_point(point: Point3D | None) -> tuple[float, float, float] | None:
  if point is None:
    return None
  return (
    _safe_float(point.x, 0.0),
    _safe_float(point.y, 0.0),
    _safe_float(point.z, 0.0),
  )


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


def _parse_rotate_delta_from_point(point: Point3D | None) -> tuple[float, float, float] | None:
  if point is None:
    return None
  return (
    _safe_float(point.x, 0.0),
    _safe_float(point.y, 0.0),
    _safe_float(point.z, 0.0),
  )


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


def _normalize_direction(
  values: tuple[float, float, float],
  fallback: tuple[float, float, float],
) -> tuple[float, float, float]:
  x, y, z = values
  length = math.sqrt(x * x + y * y + z * z)
  if length <= 1e-12:
    return fallback
  return (x / length, y / length, z / length)


def _dot3(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _cross3(
  a: tuple[float, float, float],
  b: tuple[float, float, float],
) -> tuple[float, float, float]:
  return (
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  )


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


def _read_previous_exported_position(product: Any) -> Point3D | None:
  pset_values = _read_pset_values(product, ELEMENT_STATE_PSET)
  if not pset_values:
    return None
  return _parse_point_json(pset_values.get("PositionJson"))


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


def _translate_product_by_delta(product: Any, dx: float, dy: float, dz: float) -> bool:
  placement = getattr(product, "ObjectPlacement", None)
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


def _viewer_delta_to_ifc_world(dx: float, dy: float, dz: float) -> tuple[float, float, float]:
  # Viewer uses Y-up coordinates while IFC world is Z-up in this pipeline.
  # Keep X, map viewer Y->IFC Z, and invert viewer Z when mapping to IFC Y.
  return (dx, -dz, dy)


def _viewer_rotation_to_ifc_world(rx: float, ry: float, rz: float) -> tuple[float, float, float]:
  # Viewer rotation deltas are stored in viewer axes (X right, Y up, Z forward).
  # IFC world in this pipeline is X right, Y depth, Z up.
  # Mapping follows the same basis used for translation conversion.
  return (rx, -rz, ry)


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

  hard_deleted = 0
  hard_moved = 0
  hard_rotated = 0
  hard_metadata_updates = 0
  hard_added = 0

  # 1) Hard delete only leaf IFC products.
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

  # 2) Apply placement and metadata updates on remaining IFC products.
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

  # 3) Add custom furniture as IfcFurnishingElement.
  context = _find_representation_context(model)
  for item in request.furniture:
    try:
      if _add_furniture_as_proxy(model, context, item, viewer_to_model_units, warnings):
        hard_added += 1
    except Exception as exc:  # noqa: BLE001
      warnings.append(f'Failed to add furniture "{item.id}" as IFC proxy: {exc}')

  exported_metadata = 0
  for entry in request.metadata:
    product = _safe_by_id(model, entry.ifcId)
    if product is None:
      # Deleted elements can be intentionally missing after hard export apply.
      if not entry.deleted:
        warnings.append(f"Metadata references missing ifcId {entry.ifcId}")
      continue
    try:
      pset = _get_or_create_pset(model, product, ELEMENT_STATE_PSET)
      properties: dict[str, Any] = {"StateVersion": STATE_VERSION}
      if entry.type is not None:
        properties["Type"] = entry.type
      if entry.deleted is not None:
        properties["Deleted"] = bool(entry.deleted)
      if entry.updatedAt is not None:
        properties["UpdatedAt"] = entry.updatedAt
      if entry.custom is not None:
        properties["CustomJson"] = _dump_json(entry.custom)
      if entry.position is not None:
        properties["PositionJson"] = _dump_json(entry.position.model_dump())
      if entry.moveDelta is not None:
        properties["MoveDeltaJson"] = _dump_json(entry.moveDelta.model_dump())
      if entry.rotation is not None:
        properties["RotationJson"] = _dump_json(entry.rotation.model_dump())
      if entry.rotateDelta is not None:
        properties["RotateDeltaJson"] = _dump_json(entry.rotateDelta.model_dump())
      ifcopenshell.api.run("pset.edit_pset", model, pset=pset, properties=properties)
      exported_metadata += 1
    except Exception as exc:  # noqa: BLE001
      warnings.append(f"Failed to export metadata for ifcId {entry.ifcId}: {exc}")

  project = _first_ifc_project(model)
  if project is None:
    warnings.append("No IfcProject found in source IFC, project-level state was skipped.")
  else:
    try:
      pset = _get_or_create_pset(model, project, FURNITURE_STATE_PSET)
      items = [item.model_dump(exclude_none=True) for item in request.furniture]
      ifcopenshell.api.run(
        "pset.edit_pset",
        model,
        pset=pset,
        properties={
          "StateVersion": STATE_VERSION,
          "UpdatedAt": _now_iso(),
          "ItemsJson": _dump_json(items),
        },
      )
    except Exception as exc:  # noqa: BLE001
      warnings.append(f"Failed to export furniture state: {exc}")

    try:
      pset = _get_or_create_pset(model, project, HISTORY_STATE_PSET)
      items = [item.model_dump(exclude_none=True) for item in request.history]
      ifcopenshell.api.run(
        "pset.edit_pset",
        model,
        pset=pset,
        properties={
          "StateVersion": STATE_VERSION,
          "UpdatedAt": _now_iso(),
          "ItemsJson": _dump_json(items),
        },
      )
    except Exception as exc:  # noqa: BLE001
      warnings.append(f"Failed to export history state: {exc}")

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
def health() -> dict[str, str]:
  return {"status": "ok"}


@app.post("/state/import", response_model=ImportStateResponse)
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

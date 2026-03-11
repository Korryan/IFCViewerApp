from __future__ import annotations

import json
import logging
import os
import shutil
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import ifcopenshell
import ifcopenshell.api
import ifcopenshell.util.element
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logger = logging.getLogger("ifc_ops")

DATA_ROOT = Path(os.getenv("IFC_OPS_DATA_ROOT", "/data")).resolve()
ELEMENT_STATE_PSET = "Pset_Baka_State"
FURNITURE_STATE_PSET = "Pset_Baka_Furniture"
HISTORY_STATE_PSET = "Pset_Baka_History"
STATE_VERSION = "baka-ifc-state-v1"

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
  deleted: bool | None = None
  updatedAt: str | None = None


class FurnitureItem(BaseModel):
  id: str
  model: str
  position: Point3D
  rotation: Point3D | None = None
  scale: Point3D | None = None
  roomNumber: str | None = None
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
      existing = model.by_id(pset_id)
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
        furniture_entries.append(FurnitureItem.model_validate(raw_item))
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

  exported_metadata = 0
  for entry in request.metadata:
    product = model.by_id(entry.ifcId)
    if product is None:
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

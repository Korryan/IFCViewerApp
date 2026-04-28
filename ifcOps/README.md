# ifcOps

`ifcOps` is a Python service on top of IfcOpenShell. Its main entry point is:

- [main.py](C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcOps\app\main.py)

The service is responsible for:

- taking stored editor state
- opening the source IFC
- projecting changes into a real IFC file

It is used mainly for:

- `Export file`
- `Apply changes`

## What `main.py` does

The file contains:

1. the FastAPI app
2. Pydantic request and response models
3. helpers for safe IFC entity handling
4. viewer-to-IFC coordinate conversion
5. hard export into IFC

Main endpoints:

- `GET /health`
- `POST /state/import`
- `POST /state/export`

## Data models

Important request models:

- `ImportStateRequest`
- `ExportStateRequest`

The export request contains:

- `source_ifc_path`
- `target_ifc_path`
- `metadata`
- `furniture`
- `history`

Important editor-state entities:

- `MetadataEntry`
  changes for original IFC elements
- `FurnitureItem`
  newly inserted objects, prefabs and custom mesh objects
- `HistoryEntry`
  audit or UI history of changes

## Import

Function:

- `_import_state(...)`

Current state:

- embedded editor-state import is disabled
- the endpoint returns empty:
  - `metadata`
  - `furniture`
  - `history`

This means:

- source IFC is not parsed into old editor state during upload
- editor state is kept primarily in backend JSON files

## Export pipeline

Main export function:

- `_export_state(...)`

Steps:

1. validate `source_ifc_path` and `target_ifc_path`
2. open source IFC through IfcOpenShell
3. purge technical editor PSETs
4. apply changes to original IFC entities
5. add new objects as IFC products
6. write the new IFC into `target_ifc_path`

### 1. Purging editor state

Before final export, the service removes:

- `Pset_Baka_State`
- `Pset_Baka_Furniture`
- `Pset_Baka_History`

This matters because a later upload must not reapply old editor state for position or rotation.

### 2. Original IFC elements

Changes are applied through `metadata`:

- delete
- move
- rotate
- direct attribute changes (`Name`, `Description`, `ObjectType`, `Tag`, `LongName`)
- custom PSET field changes

Relevant helpers:

- `_set_product_absolute_position(...)`
- `_rotate_product_by_delta(...)`
- `_apply_metadata_custom_updates(...)`
- `_delete_ifc_product_if_leaf(...)`

### 3. Added objects

Added objects go through `FurnitureItem`.

Export supports:

- box fallback geometry
- triangulated mesh
- IFC containers by room or space
- `IfcFurnishingElement`

Relevant helpers:

- `_resolve_furniture_container(...)`
- `_resolve_furniture_ifc_world_position(...)`
- `_create_box_representation(...)`
- `_create_mesh_representation(...)`
- `_add_furniture_as_proxy(...)`

Note:

- the `_add_furniture_as_proxy` name is historical
- it currently creates furnishing-element export, not just a generic proxy fallback

## Coordinates and transforms

`main.py` has its own layer for viewer-to-IFC conversion:

- `_viewer_point_to_ifc_world(...)`
- `_viewer_delta_to_ifc_world(...)`
- `_viewer_rotation_to_ifc_world(...)`

It also handles:

- parent placement chains
- converting world points into local parent placement coordinates
- room-relative positions of added objects
- reading the geometric room anchor from IFC representation

Relevant helpers:

- `_create_absolute_local_placement(...)`
- `_world_point_to_local_relative(...)`
- `_world_vector_to_local_relative(...)`
- `_read_space_geometric_anchor_world(...)`
- `_read_product_geometric_anchor_world(...)`

## Safety and validation

Path validation helpers:

- `_resolve_existing_path(...)`
- `_resolve_target_path(...)`

Both ensure paths stay inside:

- `IFC_OPS_DATA_ROOT`

This prevents writes outside the storage root.

Safe IFC helpers:

- `_safe_by_id(...)`
- `_safe_float(...)`
- `_safe_point_coords(...)`
- `_parse_point_json(...)`

These helpers are there to avoid export crashes on broken or incomplete data.

## Running

Most often through Docker Compose from the app root:

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp
docker compose up -d --build ifc-ops
```

The service uses:

- `IFC_OPS_DATA_ROOT=/data`

## When to edit `main.py`

`main.py` is the right place when you are changing:

- hard export into IFC
- IFC placement or rotation
- assigning new objects into rooms or storeys
- removing technical editor PSETs
- mapping viewer state into IfcOpenShell

It is not the right place for:

- UI tree
- object selection
- rooms panel
- shortcuts or camera logic

Those belong in the frontend and `IFCViewerComponent`.

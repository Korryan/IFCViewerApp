# ifcOps

`ifcOps` je Python service nad IfcOpenShell. Její hlavní vstup je:

- [main.py](C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcOps\app\main.py)

Úkol služby:

- vzít uložený editor state
- otevřít source IFC
- promítnout změny do skutečného IFC souboru

Používá se hlavně pro:

- `Export file`
- `Apply changes`

## Co `main.py` dělá

Soubor obsahuje:

1. FastAPI aplikaci
2. Pydantic request/response modely
3. helpery pro bezpečnou práci s IFC entitami
4. převody viewer souřadnic do IFC souřadnic
5. hard export změn do IFC

Hlavní endpointy:

- `GET /health`
- `POST /state/import`
- `POST /state/export`

## Data modely

Nejdůležitější request modely:

- `ImportStateRequest`
- `ExportStateRequest`

Export request obsahuje:

- `source_ifc_path`
- `target_ifc_path`
- `metadata`
- `furniture`
- `history`

Nejdůležitější entity editor state:

- `MetadataEntry`
  změny nad původními IFC prvky
- `FurnitureItem`
  nové vložené objekty, prefaby a custom mesh objekty
- `HistoryEntry`
  audit / UI historie změn

## Import

Funkce:

- `_import_state(...)`

Aktuální stav:

- import embedded editor state je vypnutý
- endpoint vrací prázdné:
  - `metadata`
  - `furniture`
  - `history`

To znamená:

- source IFC se při uploadu neparsuje na starý editor state
- editor state se drží primárně v backend JSON souborech

## Export pipeline

Hlavní export je:

- `_export_state(...)`

Postup:

1. validace `source_ifc_path` a `target_ifc_path`
2. otevření source IFC přes IfcOpenShell
3. vyčištění technických editor PSETů
4. aplikace změn na původní IFC entity
5. přidání nových objektů jako IFC produktů
6. zapsání nového IFC do `target_ifc_path`

### 1. Čištění editor state

Před finálním exportem se odstraňují:

- `Pset_Baka_State`
- `Pset_Baka_Furniture`
- `Pset_Baka_History`

To je důležité proto, aby se při dalším uploadu už nepřepisovala pozice nebo rotace podle starého editor stavu.

### 2. Původní IFC prvky

Změny se aplikují přes `metadata`:

- delete
- move
- rotate
- změna přímých atributů (`Name`, `Description`, `ObjectType`, `Tag`, `LongName`)
- změna custom PSET polí

Relevantní helpery:

- `_set_product_absolute_position(...)`
- `_rotate_product_by_delta(...)`
- `_apply_metadata_custom_updates(...)`
- `_delete_ifc_product_if_leaf(...)`

### 3. Přidané objekty

Přidané objekty jdou přes `FurnitureItem`.

Export umí:

- box fallback geometrii
- triangulovaný mesh
- IFC kontejnery podle room / space
- `IfcFurnishingElement`

Relevantní helpery:

- `_resolve_furniture_container(...)`
- `_resolve_furniture_ifc_world_position(...)`
- `_create_box_representation(...)`
- `_create_mesh_representation(...)`
- `_add_furniture_as_proxy(...)`

Poznámka:

- název helperu `_add_furniture_as_proxy` je historický
- aktuálně vytváří furnishing element export, ne jen obecný proxy fallback

## Souřadnice a transformace

`main.py` má vlastní vrstvu pro převod viewer souřadnic do IFC:

- `_viewer_point_to_ifc_world(...)`
- `_viewer_delta_to_ifc_world(...)`
- `_viewer_rotation_to_ifc_world(...)`

Dále řeší:

- parent placement chain
- převod world bodu do lokálního parent placementu
- room-relative pozice přidaných objektů
- čtení geometrického anchoru room z IFC reprezentace

Relevantní helpery:

- `_create_absolute_local_placement(...)`
- `_world_point_to_local_relative(...)`
- `_world_vector_to_local_relative(...)`
- `_read_space_geometric_anchor_world(...)`
- `_read_product_geometric_anchor_world(...)`

## Bezpečnost a validace

Souborové cesty:

- `_resolve_existing_path(...)`
- `_resolve_target_path(...)`

Obě hlídají, aby cesty zůstaly uvnitř:

- `IFC_OPS_DATA_ROOT`

To brání zápisu mimo storage root.

Bezpečné IFC helpery:

- `_safe_by_id(...)`
- `_try_json_dict(...)`
- `_try_json_point(...)`
- `_try_json_list(...)`

Tyto helpery mají zabránit pádu exportu na rozbitých nebo neúplných datech.

## Spuštění

Nejčastěji přes Docker Compose z rootu aplikace:

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp
docker compose up -d --build ifc-ops
```

Service používá:

- `IFC_OPS_DATA_ROOT=/data`

## Kdy sáhnout do `main.py`

`main.py` je správné místo pro změny, když řešíš:

- hard export do IFC
- IFC placement / rotaci
- přiřazení nových objektů do room nebo storey
- čištění technických PSETů
- mapování viewer state do IfcOpenShell

Není to správné místo pro:

- UI strom
- výběr objektů
- rooms panel
- shortcuty nebo kameru

Ty patří do frontendu a `IFCViewerComponent`.


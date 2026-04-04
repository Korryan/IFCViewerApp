# IFCViewerApp

IFC model editor with three runtime parts:

1. `ifcViewer`
   React + Vite frontend.
2. `backend`
   Spring Boot API for storing models, prefabs and editor state.
3. `ifcOps`
   Python FastAPI service on top of IfcOpenShell for hard export into IFC.

The repo also expects a sibling directory:

- `../IFCViewerComponent`

The frontend depends on it through a local `file:` dependency and the Docker build packs it into a tarball.

## Architecture

Data flow:

1. The frontend loads an IFC model from the backend.
2. Viewer changes are stored as:
   - `metadata`
   - `furniture`
   - `history`
3. The backend persists them per model into JSON files.
4. On `Export file` or `Apply changes`, the backend calls `ifcOps`.
5. `ifcOps` opens the source IFC through IfcOpenShell and applies the changes into a new IFC.

Responsibility split:

- Frontend handles UI, selection, transforms, tree, rooms and prefabs.
- Backend handles storage, models, prefabs and API.
- `ifcOps` handles real writes into IFC.

## Structure

```text
IFCViewerApp/
  backend/        Spring Boot API
  ifcOps/         Python IfcOpenShell service
  ifcViewer/      React frontend
  docker-compose.yml
```

Persisted backend data:

```text
<storage-base>/<projectId>/
  models/<modelId>/
    model.ifc
    model.json
    metadata.json
    furniture.json
    history.json
    exports/
      *.ifc
  prefabs/<prefabId>/
    prefab.ifc
    prefab.json
```

## Main workflow

### Upload model

1. The frontend uploads IFC through `POST /projects/{projectId}/models`.
2. The backend stores `model.ifc` and creates empty JSON files.
3. The backend calls `ifcOps /state/import`.
4. Embedded state import is currently disabled and returns empty lists.

### Normal editing

The frontend stores changes per model into:

- `metadata.json`
- `furniture.json`
- `history.json`

These changes are not written into `model.ifc` immediately.

### Export file

`Export file`:

- takes `model.ifc`
- applies current editor state
- creates a new exported IFC in `exports/`
- does not modify the stored source model

### Apply changes

`Apply changes`:

- performs the same hard export as `Export file`
- replaces the stored `model.ifc` with the result
- clears pending:
  - `metadata.json`
  - `furniture.json`
  - `history.json`

Use it when you want changes baked directly into the stored model and you no longer want them re-applied from JSON state.

### Prefabs

Prefabs are separate IFC files stored in backend storage.

Usage:

- `Upload prefab` in the top bar
- prefab selection in the `Add object` menu
- inserting a prefab under a concrete room in the tree or the room list

## API overview

Backend:

- `GET /projects/{projectId}/models`
- `POST /projects/{projectId}/models`
- `DELETE /projects/{projectId}/models/{modelId}`
- `GET /projects/{projectId}/models/{modelId}/ifc`
- `POST /projects/{projectId}/models/{modelId}/ifc/export-state`
- `POST /projects/{projectId}/models/{modelId}/ifc/apply-state`
- `GET /projects/{projectId}/models/{modelId}/metadata`
- `PUT /projects/{projectId}/models/{modelId}/metadata`
- `GET /projects/{projectId}/models/{modelId}/furniture`
- `PUT /projects/{projectId}/models/{modelId}/furniture`
- `GET /projects/{projectId}/models/{modelId}/history`
- `PUT /projects/{projectId}/models/{modelId}/history`
- `GET /projects/{projectId}/prefabs`
- `POST /projects/{projectId}/prefabs`
- `DELETE /projects/{projectId}/prefabs/{prefabId}`
- `GET /projects/{projectId}/prefabs/{prefabId}/ifc`

IfcOpenShell service:

- `GET /health`
- `POST /state/import`
- `POST /state/export`

Export layer details are in [ifcOps/README.md](C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcOps\README.md).

## Running the app

### Recommended: Docker

Run from:

- [docker-compose.yml](C:\Users\adam\Desktop\Baka\IFCViewerApp\docker-compose.yml)

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp
docker compose up -d --build
```

Ports:

- frontend: `http://localhost:3000`
- backend: `http://localhost:8081`
- `ifcOps` runs as an internal compose service

### Local frontend development

Requirement:

- `IFCViewerComponent` must exist next to `IFCViewerApp`

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcViewer
npm install
npm run dev
```

### Local backend development

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp\backend
mvn spring-boot:run
```

`ifcOps` is most practical to run through Docker.

## Important notes

1. `ifcOps /state/import` is currently a no-op.
   It does not restore embedded editor state from IFC.

2. Hard export during export or `Apply changes` removes technical editor PSETs:
   - `Pset_Baka_State`
   - `Pset_Baka_Furniture`
   - `Pset_Baka_History`

3. Frontend Docker build expects the sibling layout:
   - `IFCViewerApp`
   - `IFCViewerComponent`

4. `node_modules` and build artifacts must not be committed.
   This is covered by root `.gitignore` and `.dockerignore`.

# ifcViewer

Frontend application for IFC model work.

Stack:

- React
- TypeScript
- Vite
- `ifc-viewer-component`

## What the frontend handles

- loading stored IFC models from the backend
- viewer, selection, tree and room list
- object transforms
- metadata panel
- prefabs and the `Add object` menu
- export and `Apply changes`

## Component dependency

The frontend uses a local dependency:

- `ifc-viewer-component: file:../../IFCViewerComponent`

That means local development requires this sibling directory next to `IFCViewerApp`:

- `../IFCViewerComponent`

## Main files

- [src/App.tsx](C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcViewer\src\App.tsx)
  frontend orchestration, top toolbar, saved models, prefabs and backend API wiring

- `src/api/ifcOpenShellApi.ts`
  calls export and apply-state endpoints

- backend API base:
  - `/projects/{projectId}`

## Running

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcViewer
npm install
npm run dev
```

## Docker

Frontend Docker build is defined in:

- [Dockerfile](C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcViewer\Dockerfile)

Run through root compose:

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp
docker compose up -d --build frontend
```

## Note

More complete application-level documentation is in:

- [../README.md](C:\Users\adam\Desktop\Baka\IFCViewerApp\README.md)

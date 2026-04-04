# ifcViewer

Frontend aplikace pro práci s IFC modely.

Stack:

- React
- TypeScript
- Vite
- `ifc-viewer-component`

## Co frontend řeší

- načtení uloženého IFC modelu z backendu
- viewer, výběr, strom a room list
- transformace objektů
- metadata panel
- prefaby a `Add object` menu
- export a `Apply changes`

## Závislost na komponentě

Frontend používá lokální dependency:

- `ifc-viewer-component: file:../../IFCViewerComponent`

To znamená, že pro lokální běh musí vedle `IFCViewerApp` existovat i:

- `../IFCViewerComponent`

## Hlavní soubory

- [src/App.tsx](C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcViewer\src\App.tsx)
  orchestrace frontendu, horní toolbar, saved models, prefabs a napojení na backend API

- `src/api/ifcOpenShellApi.ts`
  volání export/apply-state endpointů

- frontend storage API volá backend pod:
  - `/projects/{projectId}`

## Spuštění

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcViewer
npm install
npm run dev
```

## Docker

Docker build frontendu je definovaný v:

- [Dockerfile](C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcViewer\Dockerfile)

Spouští se přes root compose:

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp
docker compose up -d --build frontend
```

## Poznámka

Přesnější dokumentace celé aplikace je v:

- [../README.md](C:\Users\adam\Desktop\Baka\IFCViewerApp\README.md)


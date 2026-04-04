# IFCViewerApp

Editor IFC modelů se třemi běžícími částmi:

1. `ifcViewer`
   React + Vite frontend.
2. `backend`
   Spring Boot API pro ukládání modelů, prefabů a editor state.
3. `ifcOps`
   Python FastAPI služba nad IfcOpenShell pro hard export do IFC.

Repo počítá i se sibling adresářem:

- `../IFCViewerComponent`

Frontend na něj závisí přes lokální `file:` dependency a Docker build z něj dělá tarball.

## Architektura

Tok dat:

1. Frontend načte IFC model z backendu.
2. Změny ve vieweru ukládá jako:
   - `metadata`
   - `furniture`
   - `history`
3. Backend je ukládá po modelech do JSON souborů.
4. Při `Export file` nebo `Apply changes` backend zavolá `ifcOps`.
5. `ifcOps` otevře source IFC přes IfcOpenShell a aplikuje změny do nového IFC.

Rozdělení odpovědnosti:

- Frontend řeší UI, výběr, transformace, strom, rooms a prefaby.
- Backend řeší storage, modely, prefaby a API.
- `ifcOps` řeší skutečný zápis změn do IFC.

## Struktura

```text
IFCViewerApp/
  backend/        Spring Boot API
  ifcOps/         Python IfcOpenShell service
  ifcViewer/      React frontend
  docker-compose.yml
```

Persistovaná data backendu:

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

## Hlavní workflow

### Upload modelu

1. Frontend nahraje IFC přes `POST /projects/{projectId}/models`.
2. Backend uloží `model.ifc` a vytvoří prázdné JSON soubory.
3. Backend volá `ifcOps /state/import`.
4. Aktuálně je import embedded stavu vypnutý a vrací prázdné seznamy.

### Běžná editace

Frontend ukládá změny po modelech do:

- `metadata.json`
- `furniture.json`
- `history.json`

Tyto změny se do samotného `model.ifc` nepropíšou hned.

### Export file

`Export file`:

- vezme `model.ifc`
- aplikuje aktuální editor state
- vytvoří nový exportní IFC do `exports/`
- původní uložený model nemění

### Apply changes

`Apply changes`:

- udělá stejný hard export jako `Export file`
- výsledný IFC přepíše do uloženého `model.ifc`
- vymaže pending:
  - `metadata.json`
  - `furniture.json`
  - `history.json`

Použij to ve chvíli, kdy chceš mít změny napečené přímo do uloženého modelu a nechceš je dál re-aplikovat z JSON state.

### Prefaby

Prefaby jsou samostatné IFC soubory uložené v backend storage.

Použití:

- `Upload prefab` v horní liště
- výběr prefabů v `Add object` menu
- vložení prefabu pod konkrétní room ve stromu nebo v room listu

## API přehled

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

Detail export vrstvy je v [ifcOps/README.md](C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcOps\README.md).

## Spuštění

### Doporučeně přes Docker

Spouští se z adresáře:

- [docker-compose.yml](C:\Users\adam\Desktop\Baka\IFCViewerApp\docker-compose.yml)

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp
docker compose up -d --build
```

Porty:

- frontend: `http://localhost:3000`
- backend: `http://localhost:8081`
- `ifcOps` je interní compose služba

### Lokální frontend vývoj

Podmínka:

- vedle `IFCViewerApp` musí existovat `IFCViewerComponent`

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp\ifcViewer
npm install
npm run dev
```

### Lokální backend vývoj

```powershell
cd C:\Users\adam\Desktop\Baka\IFCViewerApp\backend
mvn spring-boot:run
```

`ifcOps` je nejpraktičtější pouštět přes Docker service.

## Důležité poznámky

1. `ifcOps /state/import` je dnes no-op.
   Neobnovuje embedded editor state z IFC.

2. Hard export při exportu/`Apply changes` odstraňuje technické PSETy editoru:
   - `Pset_Baka_State`
   - `Pset_Baka_Furniture`
   - `Pset_Baka_History`

3. Docker build frontendu předpokládá sibling layout:
   - `IFCViewerApp`
   - `IFCViewerComponent`

4. `node_modules` a build artefakty se nemají commitovat.
   To je kryté root `.gitignore` a `.dockerignore`.


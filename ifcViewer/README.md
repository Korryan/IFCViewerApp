# IFC Viewer (React + Vite)

This project shows how to preview IFC models in a React (TypeScript) app with [`web-ifc-viewer`](https://github.com/IFCjs/web-ifc-viewer) and Three.js. It was scaffolded with `npm create vite@latest -- --template react-ts`.

## 1. Initialize the project

```bash
npm create vite@latest ifc-viewer-app -- --template react-ts
cd ifc-viewer-app
npm install
```

## 2. Install viewer libraries and static assets

```bash
npm install three@0.149 web-ifc web-ifc-viewer
```

Copy the IFC.js WebAssembly bundle so Vite can serve it:

```
/public
  ifc/
    web-ifc.wasm
    web-ifc-mt.wasm
    web-ifc-mt.worker.js
```

Add a sample model to `/public/test.ifc` (this repo uses the provided `bim.ifc`).

## 3. Key components

- `src/IfcViewer.tsx` creates the `IfcViewerAPI`, sets the WASM path, loads either the selected file or the bundled sample, and shows simple loading/error overlays.
- `src/App.tsx` renders `<input type="file" accept=".ifc">`, shows the selected file name, and passes the file to `IfcViewer`.

## 4. Run the dev server

```bash
npm run dev
```

Open <http://localhost:5173>. Without picking a file the viewer loads `public/test.ifc`. Once a file is chosen you can orbit and zoom the geometry with the mouse (controls are provided by `web-ifc-viewer`, which builds on Three.js).

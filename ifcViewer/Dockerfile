FROM node:20-bullseye-slim AS build
WORKDIR /workspace

RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*

# Prepare local component tarball so frontend install does not use symlinked file dependency.
COPY IFCViewerComponent /workspace/IFCViewerComponent
RUN cd /workspace/IFCViewerComponent && npm pack --pack-destination /workspace

COPY IFCViewerApp/ifcViewer/package.json /workspace/IFCViewerApp/ifcViewer/
WORKDIR /workspace/IFCViewerApp/ifcViewer

RUN COMPONENT_TGZ="$(ls /workspace/ifc-viewer-component-*.tgz | head -n 1)" \
  && npm pkg set "dependencies.ifc-viewer-component=file:${COMPONENT_TGZ}" \
  && npm install --no-optional \
  && npm install --no-save @rollup/rollup-linux-x64-gnu@4.53.1

COPY IFCViewerApp/ifcViewer /workspace/IFCViewerApp/ifcViewer

ARG VITE_API_BASE=/api/projects/1
ENV VITE_API_BASE=${VITE_API_BASE}

RUN IFC_WASM_DIR="/workspace/IFCViewerApp/ifcViewer/node_modules/web-ifc" \
  && if [ ! -f "${IFC_WASM_DIR}/web-ifc.wasm" ]; then IFC_WASM_DIR="/workspace/IFCViewerApp/ifcViewer/node_modules/ifc-viewer-component/node_modules/web-ifc"; fi \
  && mkdir -p /workspace/IFCViewerApp/ifcViewer/public/ifc \
  && cp "${IFC_WASM_DIR}/web-ifc.wasm" /workspace/IFCViewerApp/ifcViewer/public/ifc/web-ifc.wasm \
  && cp "${IFC_WASM_DIR}/web-ifc-mt.wasm" /workspace/IFCViewerApp/ifcViewer/public/ifc/web-ifc-mt.wasm \
  && if [ -f "${IFC_WASM_DIR}/web-ifc-mt.worker.js" ]; then \
       cp "${IFC_WASM_DIR}/web-ifc-mt.worker.js" /workspace/IFCViewerApp/ifcViewer/public/ifc/web-ifc-mt.worker.js; \
     elif [ -f "${IFC_WASM_DIR}/web-ifc-mt-worker.js" ]; then \
       cp "${IFC_WASM_DIR}/web-ifc-mt-worker.js" /workspace/IFCViewerApp/ifcViewer/public/ifc/web-ifc-mt.worker.js; \
     fi

RUN FRAG_WORKER="/workspace/IFCViewerApp/ifcViewer/node_modules/@thatopen/fragments/dist/Worker/worker.mjs" \
  && if [ ! -f "${FRAG_WORKER}" ]; then FRAG_WORKER="/workspace/IFCViewerApp/ifcViewer/node_modules/ifc-viewer-component/node_modules/@thatopen/fragments/dist/Worker/worker.mjs"; fi \
  && cp "${FRAG_WORKER}" /workspace/IFCViewerApp/ifcViewer/public/fragments-worker.mjs

RUN npm run build:docker

FROM nginx:1.27-alpine
COPY IFCViewerApp/ifcViewer/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/IFCViewerApp/ifcViewer/dist /usr/share/nginx/html

EXPOSE 80

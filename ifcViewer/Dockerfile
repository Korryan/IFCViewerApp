FROM node:20-bullseye-slim AS build
WORKDIR /app

RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*

WORKDIR /app/ifcViewer
COPY ifcViewer/package.json ifcViewer/package-lock.json ./

RUN npm install --no-optional \
  && npm install --no-save @rollup/rollup-linux-x64-gnu@4.53.1

COPY ifcViewer ./

ARG VITE_API_BASE=/api/projects/1
ENV VITE_API_BASE=${VITE_API_BASE}

RUN npm run build:docker

FROM nginx:1.27-alpine
COPY ifcViewer/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/ifcViewer/dist /usr/share/nginx/html

EXPOSE 80

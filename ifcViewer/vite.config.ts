import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { existsSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const appNodeModules = path.resolve(__dirname, 'node_modules')
const componentNodeModules = path.resolve(__dirname, '../../IFCViewerComponent/node_modules')

// Resolves shared viewer dependencies from the app install first and falls back to the local component install.
const resolveDependency = (packageName: string, subpath = '') => {
  const appPath = path.resolve(appNodeModules, packageName, subpath)
  if (existsSync(appPath)) {
    return appPath
  }
  return path.resolve(componentNodeModules, packageName, subpath)
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    fs: {
      allow: ['..']
    }
  },
  resolve: {
    alias: [
      { find: /^@thatopen\/fragments$/, replacement: resolveDependency('@thatopen/fragments', 'dist/index.mjs') },
      { find: /^@thatopen\/components$/, replacement: resolveDependency('@thatopen/components', 'dist/index.mjs') },
      { find: /^@thatopen\/components-front$/, replacement: resolveDependency('@thatopen/components-front', 'dist/index.js') },
      { find: 'three', replacement: resolveDependency('three') },
      { find: 'camera-controls', replacement: resolveDependency('camera-controls') },
      { find: 'web-ifc', replacement: resolveDependency('web-ifc') }
    ],
    dedupe: [
      'react',
      'react-dom',
      'three',
      'camera-controls',
      'web-ifc',
      '@thatopen/components',
      '@thatopen/components-front',
      '@thatopen/fragments'
    ]
  }
})

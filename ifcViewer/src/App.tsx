import { useEffect, useState, type ChangeEvent } from 'react'
import {
  IfcViewer,
  type FurnitureItem,
  type HistoryEntry,
  type MetadataEntry
} from 'ifc-viewer-component'
import './App.css'

function App() {
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [metadata, setMetadata] = useState<MetadataEntry[] | undefined>(undefined)
  const [furniture, setFurniture] = useState<FurnitureItem[] | undefined>(undefined)
  const [history, setHistory] = useState<HistoryEntry[] | undefined>(undefined)
  const [isHydrated, setIsHydrated] = useState(false)
  const apiBase = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080/projects/1'

  const fetchJson = async <T,>(url: string, options?: RequestInit): Promise<T> => {
    const response = await fetch(url, options)
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
    return response.json() as Promise<T>
  }

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    setSelectedFile(event.target.files?.[0] ?? null)
  }

  useEffect(() => {
    let cancelled = false
    const loadProjectData = async () => {
      try {
        const [loadedMetadata, loadedFurniture, loadedHistory] = await Promise.all([
          fetchJson<MetadataEntry[]>(`${apiBase}/metadata`),
          fetchJson<FurnitureItem[]>(`${apiBase}/furniture`),
          fetchJson<HistoryEntry[]>(`${apiBase}/history`)
        ])
        if (cancelled) return
        setMetadata(Array.isArray(loadedMetadata) ? loadedMetadata : [])
        setFurniture(Array.isArray(loadedFurniture) ? loadedFurniture : [])
        setHistory(Array.isArray(loadedHistory) ? loadedHistory : [])
      } catch (err) {
        console.error('Failed to load project data', err)
      } finally {
        if (!cancelled) {
          setIsHydrated(true)
        }
      }
    }
    loadProjectData()
    return () => {
      cancelled = true
    }
  }, [apiBase])

  useEffect(() => {
    if (!isHydrated || !metadata) return
    const timer = window.setTimeout(() => {
      void fetchJson<MetadataEntry[]>(`${apiBase}/metadata`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(metadata)
      }).catch((err) => console.error('Failed to save metadata', err))
    }, 500)
    return () => window.clearTimeout(timer)
  }, [apiBase, isHydrated, metadata])

  useEffect(() => {
    if (!isHydrated || !furniture) return
    const timer = window.setTimeout(() => {
      void fetchJson<FurnitureItem[]>(`${apiBase}/furniture`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(furniture)
      }).catch((err) => console.error('Failed to save furniture', err))
    }, 500)
    return () => window.clearTimeout(timer)
  }, [apiBase, furniture, isHydrated])

  useEffect(() => {
    if (!isHydrated || !history) return
    const timer = window.setTimeout(() => {
      void fetchJson<HistoryEntry[]>(`${apiBase}/history`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(history)
      }).catch((err) => console.error('Failed to save history', err))
    }, 500)
    return () => window.clearTimeout(timer)
  }, [apiBase, history, isHydrated])

  return (
    <div className="app">
      <header className="app__toolbar">
        <div className="file-input">
          <label htmlFor="ifc-file" className="file-input__button">
            Choose IFC file
          </label>
          <input
            id="ifc-file"
            className="file-input__native"
            type="file"
            accept=".ifc"
            onChange={handleFileChange}
          />
          <p className="file-input__info">
            {selectedFile ? `Loaded file: ${selectedFile.name}` : 'No file selected yet.'}
          </p>
        </div>
        <div className="app__intro">
          <h1>IFC Viewer</h1>
          <p>Select an .ifc file to inspect it directly in the browser.</p>
        </div>
      </header>

      {/* Viewer mounts here and receives either the picked file or the default sample */}
      <section className="viewer-shell">
        <IfcViewer
          file={selectedFile ?? undefined}
          metadata={metadata}
          furniture={furniture}
          history={history}
          onMetadataChange={setMetadata}
          onFurnitureChange={setFurniture}
          onHistoryChange={setHistory}
        />
      </section>
    </div>
  )
}

export default App

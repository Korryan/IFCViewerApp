import { useCallback, useEffect, useRef, useState, type ChangeEvent } from 'react'
import {
  IfcViewer,
  type FurnitureItem,
  type HistoryEntry,
  type MetadataEntry
} from 'ifc-viewer-component'
import { exportIfcState } from './api/ifcOpenShellApi'
import './App.css'

type StoredModelInfo = {
  modelId: string
  fileName: string
  createdAt: string
  updatedAt: string
}

function App() {
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [activeModel, setActiveModel] = useState<StoredModelInfo | null>(null)
  const [savedModels, setSavedModels] = useState<StoredModelInfo[]>([])
  const [metadata, setMetadata] = useState<MetadataEntry[] | undefined>(undefined)
  const [furniture, setFurniture] = useState<FurnitureItem[] | undefined>(undefined)
  const [history, setHistory] = useState<HistoryEntry[] | undefined>(undefined)
  const [isHydrated, setIsHydrated] = useState(false)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [isSavedModelsMenuOpen, setIsSavedModelsMenuOpen] = useState(false)
  const [isExportingIfcState, setIsExportingIfcState] = useState(false)
  const requestTokenRef = useRef(0)
  const savedModelsMenuRef = useRef<HTMLDivElement | null>(null)

  const projectApiBase = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080/projects/1'
  const activeModelApiBase = activeModel
    ? `${projectApiBase}/models/${encodeURIComponent(activeModel.modelId)}`
    : null
  const activeModelUrl =
    activeModel ? `${projectApiBase}/models/${encodeURIComponent(activeModel.modelId)}/ifc` : undefined

  const fetchJson = useCallback(async <T,>(url: string, options?: RequestInit): Promise<T> => {
    const response = await fetch(url, options)
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
    return response.json() as Promise<T>
  }, [])

  const formatTimestamp = useCallback((value?: string | null) => {
    if (!value) return ''
    const parsed = new Date(value)
    if (Number.isNaN(parsed.getTime())) return value
    return parsed.toLocaleString()
  }, [])

  const loadModelData = useCallback(
    async (modelInfo: StoredModelInfo, options?: { localFile?: File | null; status?: string | null }) => {
      const token = ++requestTokenRef.current
      setErrorMessage(null)
      setStatusMessage(options?.status ?? null)
      setIsHydrated(false)
      setActiveModel(modelInfo)
      setSelectedFile(options?.localFile ?? null)

      const modelApiBase = `${projectApiBase}/models/${encodeURIComponent(modelInfo.modelId)}`
      try {
        const [loadedMetadata, loadedFurniture, loadedHistory] = await Promise.all([
          fetchJson<MetadataEntry[]>(`${modelApiBase}/metadata`),
          fetchJson<FurnitureItem[]>(`${modelApiBase}/furniture`),
          fetchJson<HistoryEntry[]>(`${modelApiBase}/history`)
        ])
        if (requestTokenRef.current !== token) return

        setMetadata(Array.isArray(loadedMetadata) ? loadedMetadata : [])
        setFurniture(Array.isArray(loadedFurniture) ? loadedFurniture : [])
        setHistory(Array.isArray(loadedHistory) ? loadedHistory : [])
        setStatusMessage(null)
        setIsHydrated(true)
      } catch (err) {
        if (requestTokenRef.current !== token) return
        console.error('Failed to load model-scoped project data', err)
        setMetadata([])
        setFurniture([])
        setHistory([])
        setStatusMessage(null)
        setErrorMessage('Failed to load saved metadata for the selected model.')
      }
    },
    [fetchJson, projectApiBase]
  )

  const refreshSavedModels = useCallback(async (): Promise<StoredModelInfo[]> => {
    const models = await fetchJson<StoredModelInfo[]>(`${projectApiBase}/models`)
    const normalized = Array.isArray(models) ? models : []
    setSavedModels(normalized)
    return normalized
  }, [fetchJson, projectApiBase])

  const handleSelectSavedModel = useCallback(
    async (modelInfo: StoredModelInfo) => {
      setIsSavedModelsMenuOpen(false)
      await loadModelData(modelInfo, {
        localFile: null,
        status: `Loading saved model ${modelInfo.fileName}...`
      })
    },
    [loadModelData]
  )

  const handleFileChange = useCallback(
    async (event: ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0] ?? null
      event.currentTarget.value = ''
      if (!file) return

      const token = ++requestTokenRef.current
      setErrorMessage(null)
      setStatusMessage(`Uploading ${file.name}...`)
      setIsHydrated(false)
      setMetadata([])
      setFurniture([])
      setHistory([])

      try {
        const formData = new FormData()
        formData.append('file', file)
        const uploadedModel = await fetchJson<StoredModelInfo>(`${projectApiBase}/models`, {
          method: 'POST',
          body: formData
        })
        if (requestTokenRef.current !== token) return
        setSavedModels((prev) => [uploadedModel, ...prev.filter((item) => item.modelId !== uploadedModel.modelId)])
        setIsSavedModelsMenuOpen(false)

        await loadModelData(uploadedModel, {
          localFile: file,
          status: `Loading saved changes for ${uploadedModel.fileName}...`
        })
      } catch (err) {
        if (requestTokenRef.current !== token) return
        console.error('Failed to upload IFC file', err)
        setStatusMessage(null)
        setErrorMessage('Failed to upload IFC file to backend storage.')
      }
    },
    [fetchJson, loadModelData, projectApiBase]
  )

  const handleExportIfcState = useCallback(async () => {
    if (!activeModel || !activeModelApiBase) return
    setErrorMessage(null)
    setStatusMessage(`Exporting IFC state for ${activeModel.fileName}...`)
    setIsExportingIfcState(true)
    try {
      const result = await exportIfcState(activeModelApiBase)
      const exportUrl = `${activeModelApiBase}/ifc/exports/${encodeURIComponent(result.exportFileName)}`
      const link = document.createElement('a')
      link.href = exportUrl
      link.download = result.exportFileName
      document.body.appendChild(link)
      link.click()
      link.remove()

      const warningSuffix = result.warnings.length > 0 ? ` (${result.warnings.length} warnings)` : ''
      setStatusMessage(
        `IfcOpenShell export complete: ${result.exportedMetadata} metadata, ` +
          `${result.exportedFurniture} furniture, ${result.exportedHistory} history${warningSuffix}.`
      )
    } catch (err) {
      console.error('IfcOpenShell export failed', err)
      setStatusMessage(null)
      setErrorMessage('IfcOpenShell export failed. Check backend and ifc-ops logs.')
    } finally {
      setIsExportingIfcState(false)
    }
  }, [activeModel, activeModelApiBase])

  useEffect(() => {
    const token = ++requestTokenRef.current
    const bootstrap = async () => {
      setErrorMessage(null)
      setStatusMessage('Loading saved models...')
      try {
        const models = await refreshSavedModels()
        if (requestTokenRef.current !== token) return

        const latest = Array.isArray(models) ? models[0] : null
        if (!latest) {
          setActiveModel(null)
          setSelectedFile(null)
          setMetadata([])
          setFurniture([])
          setHistory([])
          setIsHydrated(false)
          setStatusMessage(null)
          return
        }

        await loadModelData(latest, {
          localFile: null,
          status: `Loading saved model ${latest.fileName}...`
        })
      } catch (err) {
        if (requestTokenRef.current !== token) return
        console.error('Failed to load saved models', err)
        setActiveModel(null)
        setSelectedFile(null)
        setMetadata([])
        setFurniture([])
        setHistory([])
        setIsHydrated(false)
        setStatusMessage(null)
        setErrorMessage('Failed to load saved models from backend.')
      }
    }

    void bootstrap()
    return () => {
      requestTokenRef.current += 1
    }
  }, [loadModelData, refreshSavedModels])

  useEffect(() => {
    if (!isSavedModelsMenuOpen) return

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null
      if (!target) return
      if (savedModelsMenuRef.current?.contains(target)) return
      setIsSavedModelsMenuOpen(false)
    }

    window.addEventListener('pointerdown', handlePointerDown)
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown)
    }
  }, [isSavedModelsMenuOpen])

  useEffect(() => {
    if (!isHydrated || !activeModelApiBase || !metadata) return
    const timer = window.setTimeout(() => {
      void fetchJson<MetadataEntry[]>(`${activeModelApiBase}/metadata`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(metadata)
      }).catch((err) => console.error('Failed to save metadata', err))
    }, 500)
    return () => window.clearTimeout(timer)
  }, [activeModelApiBase, fetchJson, isHydrated, metadata])

  useEffect(() => {
    if (!isHydrated || !activeModelApiBase || !furniture) return
    const timer = window.setTimeout(() => {
      void fetchJson<FurnitureItem[]>(`${activeModelApiBase}/furniture`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(furniture)
      }).catch((err) => console.error('Failed to save furniture', err))
    }, 500)
    return () => window.clearTimeout(timer)
  }, [activeModelApiBase, fetchJson, furniture, isHydrated])

  useEffect(() => {
    if (!isHydrated || !activeModelApiBase || !history) return
    const timer = window.setTimeout(() => {
      void fetchJson<HistoryEntry[]>(`${activeModelApiBase}/history`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(history)
      }).catch((err) => console.error('Failed to save history', err))
    }, 500)
    return () => window.clearTimeout(timer)
  }, [activeModelApiBase, fetchJson, history, isHydrated])

  return (
    <div className="app">
      <header className="app__toolbar">
        <div className="file-input">
          <div className="file-input__actions">
            <label htmlFor="ifc-file" className="file-input__button">
              Choose IFC file
            </label>
            <div className="file-input__menu-wrap" ref={savedModelsMenuRef}>
              <button
                type="button"
                className="file-input__button file-input__button--secondary"
                onClick={() => {
                  const nextOpen = !isSavedModelsMenuOpen
                  setIsSavedModelsMenuOpen(nextOpen)
                  if (nextOpen) {
                    void refreshSavedModels().catch((err) => {
                      console.error('Failed to refresh saved models list', err)
                    })
                  }
                }}
                aria-haspopup="menu"
                aria-expanded={isSavedModelsMenuOpen}
              >
                Saved models{savedModels.length > 0 ? ` (${savedModels.length})` : ''}
              </button>
              {isSavedModelsMenuOpen && (
                <div className="file-input__menu" role="menu" aria-label="Saved models">
                  {savedModels.length === 0 ? (
                    <p className="file-input__menu-empty">No saved models yet.</p>
                  ) : (
                    savedModels.map((model) => (
                      <button
                        key={model.modelId}
                        type="button"
                        role="menuitem"
                        className={[
                          'file-input__menu-item',
                          activeModel?.modelId === model.modelId ? 'file-input__menu-item--active' : ''
                        ]
                          .filter(Boolean)
                          .join(' ')}
                        onClick={() => {
                          void handleSelectSavedModel(model)
                        }}
                        title={`${model.fileName} (${model.modelId})`}
                      >
                        <span className="file-input__menu-item-name">{model.fileName}</span>
                        <span className="file-input__menu-item-meta">
                          {model.modelId}
                          {model.updatedAt ? ` | ${formatTimestamp(model.updatedAt)}` : ''}
                        </span>
                      </button>
                    ))
                  )}
                </div>
              )}
            </div>
          </div>
          <div className="file-input__actions">
            <button
              type="button"
              className="file-input__button file-input__button--secondary"
              onClick={() => {
                void handleExportIfcState()
              }}
              disabled={!activeModelApiBase || isExportingIfcState}
            >
              {isExportingIfcState ? 'Exporting...' : 'Export IFC (IfcOpenShell)'}
            </button>
          </div>
          <input
            id="ifc-file"
            className="file-input__native"
            type="file"
            accept=".ifc"
            onChange={handleFileChange}
          />
          <p className="file-input__info">
            {selectedFile
              ? `Loaded local file: ${selectedFile.name}`
              : activeModel
                ? `Loaded saved model: ${activeModel.fileName}`
                : 'No file selected yet.'}
          </p>
          {activeModel && (
            <p className="file-input__info">Storage folder key: {activeModel.modelId}</p>
          )}
          {statusMessage && <p className="file-input__info">{statusMessage}</p>}
          {errorMessage && <p className="file-input__info">{errorMessage}</p>}
        </div>
        <div className="app__intro">
          <h1>IFC Viewer</h1>
          <p>
            Upload an .ifc file to store it in the backend together with metadata, furniture and
            history JSON files.
          </p>
        </div>
      </header>

      <section className="viewer-shell">
        <IfcViewer
          file={selectedFile ?? undefined}
          defaultModelUrl={selectedFile ? undefined : activeModelUrl}
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

import { useCallback, useEffect, useRef, useState, type ChangeEvent } from 'react'
import {
  IfcViewer,
  type FurnitureItem,
  type HistoryEntry,
  type MetadataEntry
} from 'ifc-viewer-component'
import { applyIfcState, exportIfcState } from './api/ifcOpenShellApi'
import './App.css'

type StoredModelInfo = {
  modelId: string
  fileName: string
  createdAt: string
  updatedAt: string
}

type StoredPrefabInfo = {
  prefabId: string
  fileName: string
  createdAt: string
  updatedAt: string
}

const TrashIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path
      fill="currentColor"
      d="M9 3h6l1 2h4v2H4V5h4l1-2Zm1 6h2v8h-2V9Zm4 0h2v8h-2V9ZM7 9h2v8H7V9Zm-1 12a2 2 0 0 1-2-2V8h16v11a2 2 0 0 1-2 2H6Z"
    />
  </svg>
)

function App() {
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [activeModel, setActiveModel] = useState<StoredModelInfo | null>(null)
  const [savedModels, setSavedModels] = useState<StoredModelInfo[]>([])
  const [savedPrefabs, setSavedPrefabs] = useState<StoredPrefabInfo[]>([])
  const [metadata, setMetadata] = useState<MetadataEntry[] | undefined>(undefined)
  const [furniture, setFurniture] = useState<FurnitureItem[] | undefined>(undefined)
  const [history, setHistory] = useState<HistoryEntry[] | undefined>(undefined)
  const [isHydrated, setIsHydrated] = useState(false)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [isSavedModelsMenuOpen, setIsSavedModelsMenuOpen] = useState(false)
  const [isPrefabsMenuOpen, setIsPrefabsMenuOpen] = useState(false)
  const [isExportingIfcState, setIsExportingIfcState] = useState(false)
  const [isApplyingIfcState, setIsApplyingIfcState] = useState(false)
  const requestTokenRef = useRef(0)
  const savedModelsMenuRef = useRef<HTMLDivElement | null>(null)
  const prefabsMenuRef = useRef<HTMLDivElement | null>(null)

  const projectApiBase = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080/projects/1'
  const activeModelApiBase = activeModel
    ? `${projectApiBase}/models/${encodeURIComponent(activeModel.modelId)}`
    : null
  const activeModelUrl =
    activeModel
      ? `${projectApiBase}/models/${encodeURIComponent(activeModel.modelId)}/ifc?updatedAt=${encodeURIComponent(activeModel.updatedAt ?? '')}`
      : undefined

  const fetchJson = useCallback(async <T,>(url: string, options?: RequestInit): Promise<T> => {
    const response = await fetch(url, options)
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
    return response.json() as Promise<T>
  }, [])

  const fetchOk = useCallback(async (url: string, options?: RequestInit): Promise<void> => {
    const response = await fetch(url, options)
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
  }, [])

  const formatTimestamp = useCallback((value?: string | null) => {
    if (!value) return ''
    const parsed = new Date(value)
    if (Number.isNaN(parsed.getTime())) return value
    return parsed.toLocaleString()
  }, [])

  const resetActiveModelState = useCallback(() => {
    requestTokenRef.current += 1
    setActiveModel(null)
    setSelectedFile(null)
    setMetadata([])
    setFurniture([])
    setHistory([])
    setIsHydrated(false)
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

  const refreshSavedPrefabs = useCallback(async (): Promise<StoredPrefabInfo[]> => {
    const prefabs = await fetchJson<StoredPrefabInfo[]>(`${projectApiBase}/prefabs`)
    const normalized = Array.isArray(prefabs) ? prefabs : []
    setSavedPrefabs(normalized)
    return normalized
  }, [fetchJson, projectApiBase])

  const ensureDefaultCubePrefab = useCallback(
    async (prefabs: StoredPrefabInfo[]): Promise<StoredPrefabInfo[]> => {
      const hasCubePrefab = prefabs.some((item) => item.fileName.trim().toLowerCase() === 'cube.ifc')
      if (hasCubePrefab) {
        return prefabs
      }

      try {
        const response = await fetch('/prefabs/cube.ifc')
        if (!response.ok) {
          return prefabs
        }
        const blob = await response.blob()
        const formData = new FormData()
        formData.append('file', blob, 'cube.ifc')
        const uploadedPrefab = await fetchJson<StoredPrefabInfo>(`${projectApiBase}/prefabs`, {
          method: 'POST',
          body: formData
        })
        const nextPrefabs = [
          uploadedPrefab,
          ...prefabs.filter((item) => item.prefabId !== uploadedPrefab.prefabId)
        ]
        setSavedPrefabs(nextPrefabs)
        return nextPrefabs
      } catch (err) {
        console.warn('Failed to seed default cube prefab', err)
        return prefabs
      }
    },
    [fetchJson, projectApiBase]
  )

  const resolvePrefabFile = useCallback(
    async (prefabId: string): Promise<File | null> => {
      const prefabInfo = savedPrefabs.find((item) => item.prefabId === prefabId)
      const response = await fetch(`${projectApiBase}/prefabs/${encodeURIComponent(prefabId)}/ifc`)
      if (!response.ok) {
        throw new Error(`Failed to load prefab ${prefabId}: ${response.status}`)
      }
      const blob = await response.blob()
      return new File([blob], prefabInfo?.fileName ?? `${prefabId}.ifc`, {
        type: blob.type || 'application/octet-stream'
      })
    },
    [projectApiBase, savedPrefabs]
  )

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

  const handleDeleteSavedModel = useCallback(
    async (modelInfo: StoredModelInfo) => {
      const confirmed = window.confirm(`Delete saved model "${modelInfo.fileName}"?`)
      if (!confirmed) return

      try {
        await fetchOk(`${projectApiBase}/models/${encodeURIComponent(modelInfo.modelId)}`, {
          method: 'DELETE'
        })
        const nextModels = await refreshSavedModels()
        if (activeModel?.modelId === modelInfo.modelId) {
          resetActiveModelState()
          setStatusMessage(`Deleted saved model ${modelInfo.fileName}.`)
        } else {
          setStatusMessage(`Deleted saved model ${modelInfo.fileName}.`)
        }
        if (nextModels.length === 0) {
          setIsSavedModelsMenuOpen(false)
        }
      } catch (err) {
        console.error('Failed to delete saved model', err)
        setErrorMessage('Failed to delete saved model.')
      }
    },
    [activeModel?.modelId, fetchOk, projectApiBase, refreshSavedModels, resetActiveModelState]
  )

  const handleDownloadPrefab = useCallback(
    (prefab: StoredPrefabInfo) => {
      const link = document.createElement('a')
      link.href = `${projectApiBase}/prefabs/${encodeURIComponent(prefab.prefabId)}/ifc`
      link.download = prefab.fileName
      document.body.appendChild(link)
      link.click()
      link.remove()
    },
    [projectApiBase]
  )

  const handleDeletePrefab = useCallback(
    async (prefab: StoredPrefabInfo) => {
      const confirmed = window.confirm(`Delete prefab "${prefab.fileName}"?`)
      if (!confirmed) return

      try {
        await fetchOk(`${projectApiBase}/prefabs/${encodeURIComponent(prefab.prefabId)}`, {
          method: 'DELETE'
        })
        const nextPrefabs = await refreshSavedPrefabs()
        setStatusMessage(`Deleted prefab ${prefab.fileName}.`)
        if (nextPrefabs.length === 0) {
          setIsPrefabsMenuOpen(false)
        }
      } catch (err) {
        console.error('Failed to delete prefab', err)
        setErrorMessage('Failed to delete prefab.')
      }
    },
    [fetchOk, projectApiBase, refreshSavedPrefabs]
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

  const handlePrefabUpload = useCallback(
    async (event: ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0] ?? null
      event.currentTarget.value = ''
      if (!file) return

      setErrorMessage(null)
      setStatusMessage(`Uploading prefab ${file.name}...`)

      try {
        const formData = new FormData()
        formData.append('file', file)
        const uploadedPrefab = await fetchJson<StoredPrefabInfo>(`${projectApiBase}/prefabs`, {
          method: 'POST',
          body: formData
        })
        setSavedPrefabs((prev) => [
          uploadedPrefab,
          ...prev.filter((item) => item.prefabId !== uploadedPrefab.prefabId)
        ])
        setIsPrefabsMenuOpen(true)
        setStatusMessage(`Uploaded prefab ${uploadedPrefab.fileName}.`)
      } catch (err) {
        console.error('Failed to upload prefab IFC file', err)
        setStatusMessage(null)
        setErrorMessage('Failed to upload prefab IFC file to backend storage.')
      }
    },
    [fetchJson, projectApiBase]
  )

  const handleExportIfcState = useCallback(async () => {
    if (!activeModel || !activeModelApiBase) return
    setErrorMessage(null)
    setStatusMessage(`Exporting IFC state for ${activeModel.fileName}...`)
    setIsExportingIfcState(true)
    try {
      const result = await exportIfcState(activeModelApiBase, {
        metadata: metadata ?? [],
        furniture: furniture ?? [],
        history: history ?? []
      })
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
  }, [activeModel, activeModelApiBase, furniture, history, metadata])

  const handleApplyIfcState = useCallback(async () => {
    if (!activeModel || !activeModelApiBase) return
    const confirmed = window.confirm(
      `Apply all saved changes into "${activeModel.fileName}" and clear pending saved changes?`
    )
    if (!confirmed) return

    setErrorMessage(null)
    setStatusMessage(`Applying changes into ${activeModel.fileName}...`)
    setIsApplyingIfcState(true)
    try {
      const result = await applyIfcState(activeModelApiBase, {
        metadata: metadata ?? [],
        furniture: furniture ?? [],
        history: history ?? []
      })

      setSavedModels((prev) => [
        result.model,
        ...prev.filter((item) => item.modelId !== result.model.modelId)
      ])

      await loadModelData(result.model, {
        localFile: null,
        status: `Reloading ${result.model.fileName}...`
      })

      const warningSuffix = result.warnings.length > 0 ? ` (${result.warnings.length} warnings)` : ''
      setStatusMessage(
        `Applied changes into saved IFC: ${result.appliedMetadata} metadata, ` +
          `${result.appliedFurniture} furniture, ${result.appliedHistory} history${warningSuffix}.`
      )
    } catch (err) {
      console.error('IfcOpenShell apply-state failed', err)
      setStatusMessage(null)
      setErrorMessage('Applying changes into the saved IFC failed. Check backend and ifc-ops logs.')
    } finally {
      setIsApplyingIfcState(false)
    }
  }, [activeModel, activeModelApiBase, furniture, history, loadModelData, metadata])

  useEffect(() => {
    const token = ++requestTokenRef.current
    const bootstrap = async () => {
      setErrorMessage(null)
      setStatusMessage('Loading saved assets...')
      try {
        const [, prefabs] = await Promise.all([refreshSavedModels(), refreshSavedPrefabs()])
        await ensureDefaultCubePrefab(prefabs)
        if (requestTokenRef.current !== token) return

        resetActiveModelState()
        setStatusMessage(null)
      } catch (err) {
        if (requestTokenRef.current !== token) return
        console.error('Failed to load saved assets', err)
        resetActiveModelState()
        setStatusMessage(null)
        setErrorMessage('Failed to load saved models or prefabs from backend.')
      }
    }

    void bootstrap()
    return () => {
      requestTokenRef.current += 1
    }
  }, [ensureDefaultCubePrefab, refreshSavedModels, refreshSavedPrefabs, resetActiveModelState])

  useEffect(() => {
    if (!isSavedModelsMenuOpen && !isPrefabsMenuOpen) return

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null
      if (!target) return
      if (savedModelsMenuRef.current?.contains(target)) return
      if (prefabsMenuRef.current?.contains(target)) return
      setIsSavedModelsMenuOpen(false)
      setIsPrefabsMenuOpen(false)
    }

    window.addEventListener('pointerdown', handlePointerDown)
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown)
    }
  }, [isPrefabsMenuOpen, isSavedModelsMenuOpen])

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
        <div className="app__toolbar-main">
          <h1 className="app__title">IFCViewer</h1>
          <div className="file-input__actions">
            <label htmlFor="ifc-file" className="file-input__button">
              Upload file
            </label>
            <button
              type="button"
              className="file-input__button file-input__button--secondary"
              onClick={() => {
                void handleExportIfcState()
              }}
              disabled={!activeModelApiBase || isExportingIfcState || isApplyingIfcState}
            >
              {isExportingIfcState ? 'Exporting...' : 'Export file'}
            </button>
            <button
              type="button"
              className="file-input__button file-input__button--secondary"
              onClick={() => {
                void handleApplyIfcState()
              }}
              disabled={!activeModelApiBase || isExportingIfcState || isApplyingIfcState}
            >
              {isApplyingIfcState ? 'Applying...' : 'Apply changes'}
            </button>
            <div className="file-input__menu-wrap" ref={savedModelsMenuRef}>
              <button
                type="button"
                className="file-input__button file-input__button--secondary"
                onClick={() => {
                  const nextOpen = !isSavedModelsMenuOpen
                  setIsSavedModelsMenuOpen(nextOpen)
                  setIsPrefabsMenuOpen(false)
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
                      <div
                        key={model.modelId}
                        className={[
                          'file-input__menu-item',
                          activeModel?.modelId === model.modelId ? 'file-input__menu-item--active' : ''
                        ]
                          .filter(Boolean)
                          .join(' ')}
                      >
                        <button
                          type="button"
                          role="menuitem"
                          className="file-input__menu-main"
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
                        <button
                          type="button"
                          className="file-input__menu-delete"
                          onClick={(event) => {
                            event.stopPropagation()
                            void handleDeleteSavedModel(model)
                          }}
                          aria-label={`Delete saved model ${model.fileName}`}
                          title="Delete saved model"
                        >
                          <TrashIcon />
                        </button>
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>
            <div className="file-input__menu-wrap" ref={prefabsMenuRef}>
              <button
                type="button"
                className="file-input__button file-input__button--secondary"
                onClick={() => {
                  const nextOpen = !isPrefabsMenuOpen
                  setIsPrefabsMenuOpen(nextOpen)
                  setIsSavedModelsMenuOpen(false)
                  if (nextOpen) {
                    void refreshSavedPrefabs().catch((err) => {
                      console.error('Failed to refresh prefab list', err)
                    })
                  }
                }}
                aria-haspopup="menu"
                aria-expanded={isPrefabsMenuOpen}
              >
                Upload prefab
              </button>
              {isPrefabsMenuOpen && (
                <div className="file-input__menu" role="menu" aria-label="Prefabs">
                  <label htmlFor="prefab-file" className="file-input__menu-action">
                    Upload new prefab
                  </label>
                  {savedPrefabs.length === 0 ? (
                    <p className="file-input__menu-empty">No prefabs saved yet.</p>
                  ) : (
                    savedPrefabs.map((prefab) => (
                      <div key={prefab.prefabId} className="file-input__menu-item">
                        <button
                          type="button"
                          role="menuitem"
                          className="file-input__menu-main"
                          onClick={() => {
                            handleDownloadPrefab(prefab)
                          }}
                          title={`${prefab.fileName} (${prefab.prefabId})`}
                        >
                          <span className="file-input__menu-item-name">{prefab.fileName}</span>
                          <span className="file-input__menu-item-meta">
                            {prefab.prefabId}
                            {prefab.updatedAt ? ` | ${formatTimestamp(prefab.updatedAt)}` : ''}
                          </span>
                        </button>
                        <button
                          type="button"
                          className="file-input__menu-delete"
                          onClick={(event) => {
                            event.stopPropagation()
                            void handleDeletePrefab(prefab)
                          }}
                          aria-label={`Delete prefab ${prefab.fileName}`}
                          title="Delete prefab"
                        >
                          <TrashIcon />
                        </button>
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
        <input
          id="ifc-file"
          className="file-input__native"
          type="file"
          accept=".ifc"
          onChange={handleFileChange}
        />
        <input
          id="prefab-file"
          className="file-input__native"
          type="file"
          accept=".ifc"
          onChange={handlePrefabUpload}
        />
        <div className="app__status">
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
      </header>

      <section className="viewer-shell">
        <IfcViewer
          file={selectedFile ?? undefined}
          defaultModelUrl={selectedFile ? undefined : activeModelUrl}
          metadata={metadata}
          furniture={furniture}
          history={history}
          prefabs={savedPrefabs}
          onMetadataChange={setMetadata}
          onFurnitureChange={setFurniture}
          onHistoryChange={setHistory}
          onResolvePrefabFile={resolvePrefabFile}
        />
      </section>
    </div>
  )
}

export default App

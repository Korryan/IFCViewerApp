import { useCallback, useEffect, useRef, useState, type ChangeEvent } from 'react'
import {
  IfcViewer,
  type FurnitureItem,
  type HistoryEntry,
  type MetadataEntry
} from 'ifc-viewer-component'
import { applyIfcState, exportIfcState } from './api/ifcOpenShellApi'
import { fetchJson, fetchOk } from './app/appApi'
import { AppToolbar } from './app/AppToolbar'
import type { StoredModelInfo, StoredPrefabInfo } from './app/appTypes'
import './App.css'

// Renders the main application shell and coordinates backend persistence around the IFC viewer.
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
  const [isExportingIfcState, setIsExportingIfcState] = useState(false)
  const [isApplyingIfcState, setIsApplyingIfcState] = useState(false)
  const requestTokenRef = useRef(0)

  const projectApiBase = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080/projects/1'
  const activeModelApiBase = activeModel
    ? `${projectApiBase}/models/${encodeURIComponent(activeModel.modelId)}`
    : null
  const activeModelUrl =
    activeModel
      ? `${projectApiBase}/models/${encodeURIComponent(activeModel.modelId)}/ifc?updatedAt=${encodeURIComponent(activeModel.updatedAt ?? '')}`
      : undefined

  // This clears the active viewer model and restores one empty project state snapshot.
  const resetActiveModelState = useCallback(() => {
    requestTokenRef.current += 1
    setActiveModel(null)
    setSelectedFile(null)
    setMetadata([])
    setFurniture([])
    setHistory([])
    setIsHydrated(false)
  }, [])

  // This loads saved metadata, furniture, and history for one model selected in the UI.
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

  // This refreshes the backend list of stored IFC models and updates local menu state.
  const refreshSavedModels = useCallback(async (): Promise<StoredModelInfo[]> => {
    const models = await fetchJson<StoredModelInfo[]>(`${projectApiBase}/models`)
    const normalized = Array.isArray(models) ? models : []
    setSavedModels(normalized)
    return normalized
  }, [fetchJson, projectApiBase])

  // This refreshes the backend list of stored prefabs and updates local menu state.
  const refreshSavedPrefabs = useCallback(async (): Promise<StoredPrefabInfo[]> => {
    const prefabs = await fetchJson<StoredPrefabInfo[]>(`${projectApiBase}/prefabs`)
    const normalized = Array.isArray(prefabs) ? prefabs : []
    setSavedPrefabs(normalized)
    return normalized
  }, [fetchJson, projectApiBase])

  // This seeds the default cube prefab into backend storage when it is still missing.
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

  // This downloads one saved prefab IFC file and wraps it as a File for viewer insertion.
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

  // This opens one stored model from backend storage and closes the menu selection flow.
  const handleSelectSavedModel = useCallback(
    async (modelInfo: StoredModelInfo) => {
      await loadModelData(modelInfo, {
        localFile: null,
        status: `Loading saved model ${modelInfo.fileName}...`
      })
    },
    [loadModelData]
  )

  // This deletes one stored model and resets the active viewer state when needed.
  const handleDeleteSavedModel = useCallback(
    async (modelInfo: StoredModelInfo) => {
      const confirmed = window.confirm(`Delete saved model "${modelInfo.fileName}"?`)
      if (!confirmed) return

      try {
        await fetchOk(`${projectApiBase}/models/${encodeURIComponent(modelInfo.modelId)}`, {
          method: 'DELETE'
        })
        await refreshSavedModels()
        if (activeModel?.modelId === modelInfo.modelId) {
          resetActiveModelState()
          setStatusMessage(`Deleted saved model ${modelInfo.fileName}.`)
        } else {
          setStatusMessage(`Deleted saved model ${modelInfo.fileName}.`)
        }
      } catch (err) {
        console.error('Failed to delete saved model', err)
        setErrorMessage('Failed to delete saved model.')
      }
    },
    [activeModel?.modelId, fetchOk, projectApiBase, refreshSavedModels, resetActiveModelState]
  )

  // This starts a browser download for one prefab IFC file already stored in the backend.
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

  // This deletes one saved prefab from backend storage after user confirmation.
  const handleDeletePrefab = useCallback(
    async (prefab: StoredPrefabInfo) => {
      const confirmed = window.confirm(`Delete prefab "${prefab.fileName}"?`)
      if (!confirmed) return

      try {
        await fetchOk(`${projectApiBase}/prefabs/${encodeURIComponent(prefab.prefabId)}`, {
          method: 'DELETE'
        })
        await refreshSavedPrefabs()
        setStatusMessage(`Deleted prefab ${prefab.fileName}.`)
      } catch (err) {
        console.error('Failed to delete prefab', err)
        setErrorMessage('Failed to delete prefab.')
      }
    },
    [fetchOk, projectApiBase, refreshSavedPrefabs]
  )

  // This uploads one new IFC file as a saved project model and then loads its persisted state.
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

  // This uploads one IFC file as a reusable prefab and refreshes local prefab state.
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
        setStatusMessage(`Uploaded prefab ${uploadedPrefab.fileName}.`)
      } catch (err) {
        console.error('Failed to upload prefab IFC file', err)
        setStatusMessage(null)
        setErrorMessage('Failed to upload prefab IFC file to backend storage.')
      }
    },
    [fetchJson, projectApiBase]
  )

  // This exports the current editor state into a downloadable IFC file through ifcOps.
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

  // This applies the pending editor state back into the stored IFC file and reloads the result.
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

  // This bootstraps saved models and prefabs when the application first mounts.
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

  // This debounces metadata persistence so the backend does not receive one write per keystroke.
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

  // This debounces furniture persistence so custom objects stay in sync with backend state.
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

  // This debounces history persistence so the backend stores the latest edit log snapshot.
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
      <AppToolbar
        selectedFile={selectedFile}
        activeModel={activeModel}
        savedModels={savedModels}
        savedPrefabs={savedPrefabs}
        statusMessage={statusMessage}
        errorMessage={errorMessage}
        activeModelApiBase={activeModelApiBase}
        isExportingIfcState={isExportingIfcState}
        isApplyingIfcState={isApplyingIfcState}
        onIfcFileChange={handleFileChange}
        onPrefabFileChange={handlePrefabUpload}
        onExportIfcState={handleExportIfcState}
        onApplyIfcState={handleApplyIfcState}
        onSelectSavedModel={handleSelectSavedModel}
        onDeleteSavedModel={handleDeleteSavedModel}
        onDownloadPrefab={handleDownloadPrefab}
        onDeletePrefab={handleDeletePrefab}
        onRefreshSavedModels={refreshSavedModels}
        onRefreshSavedPrefabs={refreshSavedPrefabs}
      />

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

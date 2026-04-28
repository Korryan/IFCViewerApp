import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { formatTimestamp } from './appApi'
import type { StoredModelInfo, StoredPrefabInfo } from './appTypes'

type AppToolbarProps = {
  selectedFile: File | null
  activeModel: StoredModelInfo | null
  savedModels: StoredModelInfo[]
  savedPrefabs: StoredPrefabInfo[]
  statusMessage: string | null
  errorMessage: string | null
  activeModelApiBase: string | null
  isExportingIfcState: boolean
  isApplyingIfcState: boolean
  onIfcFileChange: (event: ChangeEvent<HTMLInputElement>) => Promise<void>
  onPrefabFileChange: (event: ChangeEvent<HTMLInputElement>) => Promise<void>
  onExportIfcState: () => Promise<void>
  onApplyIfcState: () => Promise<void>
  onSelectSavedModel: (modelInfo: StoredModelInfo) => Promise<void>
  onDeleteSavedModel: (modelInfo: StoredModelInfo) => Promise<void>
  onDownloadPrefab: (prefab: StoredPrefabInfo) => void
  onDeletePrefab: (prefab: StoredPrefabInfo) => Promise<void>
  onRefreshSavedModels: () => Promise<StoredModelInfo[]>
  onRefreshSavedPrefabs: () => Promise<StoredPrefabInfo[]>
}

// Renders the shared delete icon used in saved-model and prefab menus.
const TrashIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path
      fill="currentColor"
      d="M9 3h6l1 2h4v2H4V5h4l1-2Zm1 6h2v8h-2V9Zm4 0h2v8h-2V9ZM7 9h2v8H7V9Zm-1 12a2 2 0 0 1-2-2V8h16v11a2 2 0 0 1-2 2H6Z"
    />
  </svg>
)

// Renders the top toolbar with file actions, saved-model menus, and hidden file inputs.
export const AppToolbar = ({
  selectedFile,
  activeModel,
  savedModels,
  savedPrefabs,
  statusMessage,
  errorMessage,
  activeModelApiBase,
  isExportingIfcState,
  isApplyingIfcState,
  onIfcFileChange,
  onPrefabFileChange,
  onExportIfcState,
  onApplyIfcState,
  onSelectSavedModel,
  onDeleteSavedModel,
  onDownloadPrefab,
  onDeletePrefab,
  onRefreshSavedModels,
  onRefreshSavedPrefabs
}: AppToolbarProps) => {
  const [isSavedModelsMenuOpen, setIsSavedModelsMenuOpen] = useState(false)
  const [isPrefabsMenuOpen, setIsPrefabsMenuOpen] = useState(false)
  const savedModelsMenuRef = useRef<HTMLDivElement | null>(null)
  const prefabsMenuRef = useRef<HTMLDivElement | null>(null)

  // This closes toolbar menus when the user presses outside either menu surface.
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

  return (
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
              void onExportIfcState()
            }}
            disabled={!activeModelApiBase || isExportingIfcState || isApplyingIfcState}
          >
            {isExportingIfcState ? 'Exporting...' : 'Export file'}
          </button>
          <button
            type="button"
            className="file-input__button file-input__button--secondary"
            onClick={() => {
              void onApplyIfcState()
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
                  void onRefreshSavedModels().catch((err) => {
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
                          void onSelectSavedModel(model)
                          setIsSavedModelsMenuOpen(false)
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
                          void onDeleteSavedModel(model)
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
                  void onRefreshSavedPrefabs().catch((err) => {
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
                          onDownloadPrefab(prefab)
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
                          void onDeletePrefab(prefab)
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
        onChange={(event) => {
          void onIfcFileChange(event)
        }}
      />
      <input
        id="prefab-file"
        className="file-input__native"
        type="file"
        accept=".ifc"
        onChange={(event) => {
          void onPrefabFileChange(event)
        }}
      />
      <div className="app__status">
        <p className="file-input__info">
          {selectedFile
            ? `Loaded local file: ${selectedFile.name}`
            : activeModel
              ? `Loaded saved model: ${activeModel.fileName}`
              : 'No file selected yet.'}
        </p>
        {activeModel && <p className="file-input__info">Storage folder key: {activeModel.modelId}</p>}
        {statusMessage && <p className="file-input__info">{statusMessage}</p>}
        {errorMessage && <p className="file-input__info">{errorMessage}</p>}
      </div>
    </header>
  )
}

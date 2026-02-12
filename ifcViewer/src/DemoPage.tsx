import { useState, type ChangeEvent } from 'react'
import {
  IfcViewer,
  type FurnitureItem,
  type HistoryEntry,
  type MetadataEntry
} from 'ifc-viewer-component'
import './DemoPage.css'

// Lightweight demo page showing the IFC viewer embedded in a custom layout.
export const DemoPage = () => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [selectionLabel, setSelectionLabel] = useState<string>('None')
  const [metadata, setMetadata] = useState<MetadataEntry[]>([])
  const [furniture, setFurniture] = useState<FurnitureItem[]>([])
  const [history, setHistory] = useState<HistoryEntry[]>([])

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    setSelectedFile(event.target.files?.[0] ?? null)
  }

  return (
    <div className="demo-page">
      <header className="demo-hero">
        <div className="demo-hero__text">
          <p className="demo-hero__eyebrow">Prototype workspace</p>
          <h1>IFC Viewer Demo</h1>
          <p className="demo-hero__subtitle">
            Standalone embedding of the viewer with a custom page layout.
          </p>
        </div>
        <div className="demo-hero__controls">
          <label htmlFor="ifc-file-demo" className="demo-upload">
            <span>Choose IFC file</span>
            <input
              id="ifc-file-demo"
              type="file"
              accept=".ifc"
              onChange={handleFileChange}
            />
          </label>
          <p className="demo-hero__file">
            {selectedFile ? `Loaded file: ${selectedFile.name}` : 'No file selected yet.'}
          </p>
        </div>
      </header>

      <div className="demo-body">
        <aside className="demo-sidebar">
          <h2>Session</h2>
          <p>Selection: {selectionLabel}</p>
          <p>Shortcuts: press ? or H</p>
          <p>Tip: Use K to pick overlapping elements.</p>
        </aside>
        <section className="demo-viewer">
          <IfcViewer
            file={selectedFile ?? undefined}
            metadata={metadata}
            furniture={furniture}
            history={history}
            onMetadataChange={setMetadata}
            onFurnitureChange={setFurniture}
            onHistoryChange={setHistory}
            showTree
            showProperties
            showShortcuts
            onSelectionChange={(selection) => {
              if (!selection) {
                setSelectionLabel('None')
              } else {
                setSelectionLabel(`#${selection.expressID}`)
              }
            }}
          />
        </section>
      </div>
    </div>
  )
}

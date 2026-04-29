import type { ViewerState } from 'ifc-viewer-component'
import { fetchJson } from './appApi'

// Returns true when one viewer-state payload contains at least one field worth restoring later.
const hasViewerStatePayload = (viewerState: ViewerState | null | undefined): viewerState is ViewerState => {
  if (!viewerState) return false
  return Boolean(
    viewerState.navigationMode ||
      viewerState.roomOnlyTransformGuard !== undefined ||
      viewerState.shortcutsOpen !== undefined ||
      viewerState.cameraPosition ||
      viewerState.cameraTarget
  )
}

// Loads one saved viewer session snapshot for the active model and normalizes empty payloads to null.
export const readViewerState = async (modelApiBase: string): Promise<ViewerState | null> => {
  const viewerState = await fetchJson<ViewerState | null>(`${modelApiBase}/viewer-state`)
  return hasViewerStatePayload(viewerState) ? viewerState : null
}

// Saves one viewer session snapshot for the active model and normalizes the echoed payload.
export const writeViewerState = async (
  modelApiBase: string,
  viewerState: ViewerState
): Promise<ViewerState | null> => {
  const savedViewerState = await fetchJson<ViewerState | null>(`${modelApiBase}/viewer-state`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(viewerState)
  })
  return hasViewerStatePayload(savedViewerState) ? savedViewerState : null
}

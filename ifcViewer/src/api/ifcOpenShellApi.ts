export type IfcImportStateResponse = {
  modelId: string
  importedMetadata: number
  importedFurniture: number
  importedHistory: number
  warnings: string[]
}

export type IfcExportStateResponse = {
  modelId: string
  exportFileName: string
  exportedMetadata: number
  exportedFurniture: number
  exportedHistory: number
  warnings: string[]
}

const parseErrorMessage = async (response: Response): Promise<string> => {
  const fallback = `Request failed: ${response.status}`
  const contentType = response.headers.get('content-type') ?? ''
  try {
    if (contentType.includes('application/json')) {
      const payload = await response.json()
      const detail = typeof payload?.detail === 'string' ? payload.detail : null
      if (detail) {
        return `${fallback} (${detail})`
      }
    } else {
      const text = await response.text()
      if (text) {
        return `${fallback} (${text.trim()})`
      }
    }
  } catch {
    return fallback
  }
  return fallback
}

const postJson = async <T>(url: string): Promise<T> => {
  const response = await fetch(url, { method: 'POST' })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json() as Promise<T>
}

export const importIfcState = async (modelApiBase: string): Promise<IfcImportStateResponse> => {
  return postJson<IfcImportStateResponse>(`${modelApiBase}/ifc/import-state`)
}

export const exportIfcState = async (modelApiBase: string): Promise<IfcExportStateResponse> => {
  return postJson<IfcExportStateResponse>(`${modelApiBase}/ifc/export-state`)
}

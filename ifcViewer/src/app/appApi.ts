// Fetches JSON from the backend and throws on non-success responses.
export const fetchJson = async <T,>(url: string, options?: RequestInit): Promise<T> => {
  const response = await fetch(url, options)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }
  return response.json() as Promise<T>
}

// Calls one backend endpoint that only needs a success or failure result.
export const fetchOk = async (url: string, options?: RequestInit): Promise<void> => {
  const response = await fetch(url, options)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }
}

// Formats one backend timestamp for the local UI locale.
export const formatTimestamp = (value?: string | null) => {
  if (!value) return ''
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleString()
}

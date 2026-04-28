// Describes one saved IFC model entry returned by the backend.
export type StoredModelInfo = {
  modelId: string
  fileName: string
  createdAt: string
  updatedAt: string
}

// Describes one saved prefab entry returned by the backend.
export type StoredPrefabInfo = {
  prefabId: string
  fileName: string
  createdAt: string
  updatedAt: string
}

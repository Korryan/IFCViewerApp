package cz.ifc.backend.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.springframework.web.multipart.MultipartFile;

final class StorageCatalogFacade {
  private final StorageCatalogPaths storagePaths;
  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<Path, ReentrantReadWriteLock> locks;
  private final Logger log;
  private final String metadataFileName;
  private final String furnitureFileName;
  private final String historyFileName;
  private final String modelManifestFileName;
  private final String prefabManifestFileName;

  // Captures the shared dependencies needed to serve stored model and prefab catalog operations.
  StorageCatalogFacade(
      StorageCatalogPaths storagePaths,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      Logger log,
      String metadataFileName,
      String furnitureFileName,
      String historyFileName,
      String modelManifestFileName,
      String prefabManifestFileName) {
    this.storagePaths = storagePaths;
    this.objectMapper = objectMapper;
    this.locks = locks;
    this.log = log;
    this.metadataFileName = metadataFileName;
    this.furnitureFileName = furnitureFileName;
    this.historyFileName = historyFileName;
    this.modelManifestFileName = modelManifestFileName;
    this.prefabManifestFileName = prefabManifestFileName;
  }

  // Stores an uploaded IFC file as a new model and initializes the editor state files for it.
  FileStorageService.StoredModelInfo storeUploadedModel(String projectId, MultipartFile file) {
    String originalFileName = StorageCatalogHelper.requireUploadedFileName(file, "Missing IFC file");
    String modelId = StoragePathHelper.buildStorageId(originalFileName);
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    Instant now = Instant.now();
    FileStorageService.StoredModelManifest manifest =
        new FileStorageService.StoredModelManifest(modelId, originalFileName, now, now);
    StorageCatalogHelper.storeUploadedIfcFile(
        file,
        modelPaths.ifcFile(),
        modelPaths.manifestFile(),
        manifest,
        objectMapper,
        locks,
        () ->
            StorageModelStateHelper.ensureModelStateFiles(
                modelPaths.directory(), metadataFileName, furnitureFileName, historyFileName, objectMapper),
        "Failed to store IFC file");
    return StorageManifestHelper.toStoredModelInfo(manifest);
  }

  // Lists stored models ordered by their last update time.
  List<FileStorageService.StoredModelInfo> listModels(String projectId) {
    return StorageCatalogHelper.listStoredInfos(
        storagePaths.modelsDir(projectId),
        modelDir ->
            StorageManifestHelper.readStoredModelInfoSafe(
                modelDir, modelManifestFileName, objectMapper, log),
        Comparator.comparing(
                FileStorageService.StoredModelInfo::updatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed(),
        "Failed to list models");
  }

  // Stores an uploaded IFC file as a new prefab entry.
  FileStorageService.StoredPrefabInfo storeUploadedPrefab(String projectId, MultipartFile file) {
    String originalFileName =
        StorageCatalogHelper.requireUploadedFileName(file, "Missing IFC prefab file");
    String prefabId = StoragePathHelper.buildStorageId(originalFileName);
    StorageCatalogPaths.PrefabPaths prefabPaths = storagePaths.prefab(projectId, prefabId);
    Instant now = Instant.now();
    FileStorageService.StoredPrefabManifest manifest =
        new FileStorageService.StoredPrefabManifest(prefabId, originalFileName, now, now);
    StorageCatalogHelper.storeUploadedIfcFile(
        file,
        prefabPaths.ifcFile(),
        prefabPaths.manifestFile(),
        manifest,
        objectMapper,
        locks,
        () -> {},
        "Failed to store prefab IFC file");
    return StorageManifestHelper.toStoredPrefabInfo(manifest);
  }

  // Lists stored prefabs ordered by their last update time.
  List<FileStorageService.StoredPrefabInfo> listPrefabs(String projectId) {
    return StorageCatalogHelper.listStoredInfos(
        storagePaths.prefabsDir(projectId),
        prefabDir ->
            StorageManifestHelper.readStoredPrefabInfoSafe(
                prefabDir, prefabManifestFileName, objectMapper, log),
        Comparator.comparing(
                FileStorageService.StoredPrefabInfo::updatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed(),
        "Failed to list prefabs");
  }

  // Resolves the stored IFC file for one model and reports missing directories or binaries.
  Path getModelIfcPath(String projectId, String modelId) {
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    return StorageCatalogHelper.requireStoredChildFile(
        modelPaths.directory(),
        "Model not found",
        modelPaths.ifcFile().getFileName().toString(),
        "IFC model file not found");
  }

  // Resolves the stored IFC file for one prefab and reports missing directories or binaries.
  Path getPrefabIfcPath(String projectId, String prefabId) {
    StorageCatalogPaths.PrefabPaths prefabPaths = storagePaths.prefab(projectId, prefabId);
    return StorageCatalogHelper.requireStoredChildFile(
        prefabPaths.directory(),
        "Prefab not found",
        prefabPaths.ifcFile().getFileName().toString(),
        "Prefab IFC file not found");
  }

  // Reads stored model manifest metadata and maps missing manifests to a not-found API error.
  FileStorageService.StoredModelInfo getModelInfo(String projectId, String modelId) {
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    return StorageCatalogHelper.requireStoredInfo(
        modelPaths.directory(),
        "Model not found",
        modelDir ->
            StorageManifestHelper.readStoredModelInfoSafe(
                modelDir, modelPaths.manifestFile().getFileName().toString(), objectMapper, log),
        "Model metadata not found");
  }

  // Replaces the stored IFC binary for one model and refreshes its manifest timestamp.
  FileStorageService.StoredModelInfo replaceModelIfc(String projectId, String modelId, Path sourceIfcPath) {
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    StorageModelStateHelper.replaceModelIfc(
        modelPaths.directory(),
        modelId,
        modelPaths.ifcFile(),
        modelPaths.manifestFile(),
        sourceIfcPath,
        objectMapper,
        locks,
        "Exported IFC file not found",
        "Failed to replace stored IFC file");
    return getModelInfo(projectId, modelId);
  }

  // Clears all model-scoped editor state files and refreshes the model manifest timestamp.
  void resetModelState(String projectId, String modelId) {
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    StorageModelStateHelper.resetModelState(
        modelPaths.directory(),
        modelId,
        modelPaths.manifestFile(),
        metadataFileName,
        furnitureFileName,
        historyFileName,
        objectMapper,
        locks,
        log);
  }

  // Reads stored prefab manifest metadata and maps missing manifests to a not-found API error.
  FileStorageService.StoredPrefabInfo getPrefabInfo(String projectId, String prefabId) {
    StorageCatalogPaths.PrefabPaths prefabPaths = storagePaths.prefab(projectId, prefabId);
    return StorageCatalogHelper.requireStoredInfo(
        prefabPaths.directory(),
        "Prefab not found",
        prefabDir ->
            StorageManifestHelper.readStoredPrefabInfoSafe(
                prefabDir, prefabPaths.manifestFile().getFileName().toString(), objectMapper, log),
        "Prefab metadata not found");
  }

  // Deletes one stored model directory together with all nested files.
  void deleteModel(String projectId, String modelId) {
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    StorageCatalogHelper.deleteStoredDirectory(modelPaths.directory(), "Model not found", "model");
  }

  // Deletes one stored prefab directory together with all nested files.
  void deletePrefab(String projectId, String prefabId) {
    StorageCatalogPaths.PrefabPaths prefabPaths = storagePaths.prefab(projectId, prefabId);
    StorageCatalogHelper.deleteStoredDirectory(prefabPaths.directory(), "Prefab not found", "prefab");
  }

  // Creates a new export path for one model under its exports directory.
  Path createModelExportIfcPath(String projectId, String modelId, String exportKind) {
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    return StorageCatalogHelper.createStoredExportIfcPath(modelPaths.directory(), "Model not found", exportKind);
  }

  // Resolves one existing exported IFC file for a stored model.
  Path getModelExportIfcPath(String projectId, String modelId, String exportFileName) {
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    return StorageCatalogHelper.getStoredExportIfcPath(modelPaths.directory(), "Model not found", exportFileName);
  }

  // Deletes a temporary file when present without surfacing cleanup failures to callers.
  void deleteIfExists(Path path) {
    StorageFileOpsHelper.deleteIfExists(path, log);
  }
}

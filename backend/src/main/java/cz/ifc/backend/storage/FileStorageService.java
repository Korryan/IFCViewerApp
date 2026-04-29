package cz.ifc.backend.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import cz.ifc.backend.model.ViewerStateSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileStorageService {
  private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
  private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final String METADATA_FILE = "metadata.json";
  private static final String FURNITURE_FILE = "furniture.json";
  private static final String HISTORY_FILE = "history.json";
  private static final String VIEWER_STATE_FILE = "viewer-state.json";
  private static final String MODELS_DIR = "models";
  private static final String MODEL_FILE = "model.ifc";
  private static final String MODEL_MANIFEST_FILE = "model.json";
  private static final String PREFABS_DIR = "prefabs";
  private static final String PREFAB_FILE = "prefab.ifc";
  private static final String PREFAB_MANIFEST_FILE = "prefab.json";
  private static final Pattern MODEL_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final Pattern PREFAB_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

  // Base directory for file-backed storage.
  private final Path baseDir;
  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<Path, ReentrantReadWriteLock> locks;
  private final StorageProjectStateFacade projectStateFacade;
  private final StorageCatalogFacade catalogFacade;

  public FileStorageService(
      @Value("${storage.base-dir:data}") String baseDir,
      ObjectMapper objectMapper) {
    this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    this.objectMapper = objectMapper;
    StorageCatalogPaths storagePaths =
        new StorageCatalogPaths(
            this.baseDir,
            PROJECT_ID_PATTERN,
            MODEL_ID_PATTERN,
            PREFAB_ID_PATTERN,
            MODELS_DIR,
            MODEL_FILE,
            MODEL_MANIFEST_FILE,
            PREFABS_DIR,
            PREFAB_FILE,
            PREFAB_MANIFEST_FILE);
    this.locks = new ConcurrentHashMap<>();
    this.projectStateFacade =
        new StorageProjectStateFacade(
            storagePaths,
            objectMapper,
            this.locks,
            log,
            METADATA_FILE,
            FURNITURE_FILE,
            HISTORY_FILE);
    this.catalogFacade =
        new StorageCatalogFacade(
            storagePaths,
            objectMapper,
            this.locks,
            log,
            METADATA_FILE,
            FURNITURE_FILE,
            HISTORY_FILE,
            MODEL_MANIFEST_FILE,
            PREFAB_MANIFEST_FILE);
  }

  @PostConstruct
  public void logBaseDir() {
    log.info("Storage base dir: {}", baseDir);
  }

  public record StoredModelInfo(String modelId, String fileName, Instant createdAt, Instant updatedAt) {}

  record StoredModelManifest(String modelId, String fileName, Instant createdAt, Instant updatedAt) {}
  public record StoredPrefabInfo(String prefabId, String fileName, Instant createdAt, Instant updatedAt) {}

  record StoredPrefabManifest(String prefabId, String fileName, Instant createdAt, Instant updatedAt) {}

  // Read project metadata list from disk.
  public List<MetadataEntry> readMetadata(String projectId) {
    return projectStateFacade.readMetadata(projectId);
  }

  public List<MetadataEntry> readMetadata(String projectId, String modelId) {
    return projectStateFacade.readMetadata(projectId, modelId);
  }

  // Write project metadata list to disk (overwrites previous file).
  public List<MetadataEntry> writeMetadata(String projectId, List<MetadataEntry> items) {
    return projectStateFacade.writeMetadata(projectId, items);
  }

  public List<MetadataEntry> writeMetadata(String projectId, String modelId, List<MetadataEntry> items) {
    return projectStateFacade.writeMetadata(projectId, modelId, items);
  }

  // Read project furniture list from disk.
  public List<FurnitureItem> readFurniture(String projectId) {
    return projectStateFacade.readFurniture(projectId);
  }

  public List<FurnitureItem> readFurniture(String projectId, String modelId) {
    return projectStateFacade.readFurniture(projectId, modelId);
  }

  // Write project furniture list to disk (overwrites previous file).
  public List<FurnitureItem> writeFurniture(String projectId, List<FurnitureItem> items) {
    return projectStateFacade.writeFurniture(projectId, items);
  }

  public List<FurnitureItem> writeFurniture(String projectId, String modelId, List<FurnitureItem> items) {
    return projectStateFacade.writeFurniture(projectId, modelId, items);
  }

  // Read project change history list from disk.
  public List<HistoryEntry> readHistory(String projectId) {
    return projectStateFacade.readHistory(projectId);
  }

  public List<HistoryEntry> readHistory(String projectId, String modelId) {
    return projectStateFacade.readHistory(projectId, modelId);
  }

  // Write project change history list to disk (overwrites previous file).
  public List<HistoryEntry> writeHistory(String projectId, List<HistoryEntry> items) {
    return projectStateFacade.writeHistory(projectId, items);
  }

  public List<HistoryEntry> writeHistory(String projectId, String modelId, List<HistoryEntry> items) {
    return projectStateFacade.writeHistory(projectId, modelId, items);
  }

  // Reads the model-scoped viewer session snapshot used to restore camera and mode state.
  public ViewerStateSnapshot readViewerState(String projectId, String modelId) {
    try {
      ViewerStateSnapshot viewerState =
          StorageJsonHelper.readValue(getViewerStatePath(projectId, modelId), ViewerStateSnapshot.class, objectMapper, locks);
      return viewerState != null ? viewerState : new ViewerStateSnapshot();
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read viewer state", ex);
    }
  }

  // Writes the model-scoped viewer session snapshot used to restore camera and mode state.
  public ViewerStateSnapshot writeViewerState(String projectId, String modelId, ViewerStateSnapshot viewerState) {
    ViewerStateSnapshot normalized = viewerState != null ? viewerState : new ViewerStateSnapshot();
    try {
      StorageJsonHelper.writeValue(getViewerStatePath(projectId, modelId), normalized, objectMapper, locks, log);
      return normalized;
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write viewer state", ex);
    }
  }

  public StoredModelInfo storeUploadedModel(String projectId, MultipartFile file) {
    return catalogFacade.storeUploadedModel(projectId, file);
  }

  public List<StoredModelInfo> listModels(String projectId) {
    return catalogFacade.listModels(projectId);
  }

  public StoredPrefabInfo storeUploadedPrefab(String projectId, MultipartFile file) {
    return catalogFacade.storeUploadedPrefab(projectId, file);
  }

  public List<StoredPrefabInfo> listPrefabs(String projectId) {
    return catalogFacade.listPrefabs(projectId);
  }

  public Path getModelIfcPath(String projectId, String modelId) {
    return catalogFacade.getModelIfcPath(projectId, modelId);
  }

  public Path getPrefabIfcPath(String projectId, String prefabId) {
    return catalogFacade.getPrefabIfcPath(projectId, prefabId);
  }

  public StoredModelInfo getModelInfo(String projectId, String modelId) {
    return catalogFacade.getModelInfo(projectId, modelId);
  }

  public StoredModelInfo replaceModelIfc(String projectId, String modelId, Path sourceIfcPath) {
    return catalogFacade.replaceModelIfc(projectId, modelId, sourceIfcPath);
  }

  public void resetModelState(String projectId, String modelId) {
    catalogFacade.resetModelState(projectId, modelId);
  }

  public void deleteIfExists(Path path) {
    catalogFacade.deleteIfExists(path);
  }

  public StoredPrefabInfo getPrefabInfo(String projectId, String prefabId) {
    return catalogFacade.getPrefabInfo(projectId, prefabId);
  }

  public void deleteModel(String projectId, String modelId) {
    catalogFacade.deleteModel(projectId, modelId);
  }

  public void deletePrefab(String projectId, String prefabId) {
    catalogFacade.deletePrefab(projectId, prefabId);
  }

  public Path createModelExportIfcPath(String projectId, String modelId, String exportKind) {
    return catalogFacade.createModelExportIfcPath(projectId, modelId, exportKind);
  }

  public Path getModelExportIfcPath(String projectId, String modelId, String exportFileName) {
    return catalogFacade.getModelExportIfcPath(projectId, modelId, exportFileName);
  }

  // Resolves the model-scoped JSON file that stores the last persisted viewer session state.
  private Path getViewerStatePath(String projectId, String modelId) {
    return getModelIfcPath(projectId, modelId).getParent().resolve(VIEWER_STATE_FILE);
  }

}

package cz.ifc.backend.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {
  private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
  private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final String METADATA_FILE = "metadata.json";
  private static final String FURNITURE_FILE = "furniture.json";
  private static final String HISTORY_FILE = "history.json";
  private static final String MODELS_DIR = "models";
  private static final String MODEL_FILE = "model.ifc";
  private static final String MODEL_MANIFEST_FILE = "model.json";
  private static final String MODEL_EXPORTS_DIR = "exports";
  private static final String PREFABS_DIR = "prefabs";
  private static final String PREFAB_FILE = "prefab.ifc";
  private static final String PREFAB_MANIFEST_FILE = "prefab.json";
  private static final Pattern MODEL_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final Pattern PREFAB_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final Pattern MODEL_EXPORT_FILE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

  // Base directory for file-backed storage.
  private final Path baseDir;
  private final ObjectMapper objectMapper;
  // Per-file locks to avoid concurrent read/write issues.
  private final ConcurrentHashMap<Path, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

  public FileStorageService(@Value("${storage.base-dir:data}") String baseDir, ObjectMapper objectMapper) {
    this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  public void logBaseDir() {
    log.info("Storage base dir: {}", baseDir);
  }

  public record StoredModelInfo(String modelId, String fileName, Instant createdAt, Instant updatedAt) {}

  private record StoredModelManifest(String modelId, String fileName, Instant createdAt, Instant updatedAt) {}
  public record StoredPrefabInfo(String prefabId, String fileName, Instant createdAt, Instant updatedAt) {}

  private record StoredPrefabManifest(String prefabId, String fileName, Instant createdAt, Instant updatedAt) {}

  // Read project metadata list from disk.
  public List<MetadataEntry> readMetadata(String projectId) {
    return readList(projectId, METADATA_FILE, new TypeReference<List<MetadataEntry>>() {});
  }

  public List<MetadataEntry> readMetadata(String projectId, String modelId) {
    return readModelList(projectId, modelId, METADATA_FILE, new TypeReference<List<MetadataEntry>>() {});
  }

  // Write project metadata list to disk (overwrites previous file).
  public List<MetadataEntry> writeMetadata(String projectId, List<MetadataEntry> items) {
    List<MetadataEntry> normalized = normalizeMetadata(items);
    writeList(projectId, METADATA_FILE, normalized);
    return normalized;
  }

  public List<MetadataEntry> writeMetadata(String projectId, String modelId, List<MetadataEntry> items) {
    List<MetadataEntry> normalized = normalizeMetadata(items);
    writeModelList(projectId, modelId, METADATA_FILE, normalized);
    return normalized;
  }

  // Read project furniture list from disk.
  public List<FurnitureItem> readFurniture(String projectId) {
    return readList(projectId, FURNITURE_FILE, new TypeReference<List<FurnitureItem>>() {});
  }

  public List<FurnitureItem> readFurniture(String projectId, String modelId) {
    return readModelList(projectId, modelId, FURNITURE_FILE, new TypeReference<List<FurnitureItem>>() {});
  }

  // Write project furniture list to disk (overwrites previous file).
  public List<FurnitureItem> writeFurniture(String projectId, List<FurnitureItem> items) {
    List<FurnitureItem> normalized = normalizeFurniture(items);
    writeList(projectId, FURNITURE_FILE, normalized);
    return normalized;
  }

  public List<FurnitureItem> writeFurniture(String projectId, String modelId, List<FurnitureItem> items) {
    List<FurnitureItem> normalized = normalizeFurniture(items);
    writeModelList(projectId, modelId, FURNITURE_FILE, normalized);
    return normalized;
  }

  // Read project change history list from disk.
  public List<HistoryEntry> readHistory(String projectId) {
    return readList(projectId, HISTORY_FILE, new TypeReference<List<HistoryEntry>>() {});
  }

  public List<HistoryEntry> readHistory(String projectId, String modelId) {
    return readModelList(projectId, modelId, HISTORY_FILE, new TypeReference<List<HistoryEntry>>() {});
  }

  // Write project change history list to disk (overwrites previous file).
  public List<HistoryEntry> writeHistory(String projectId, List<HistoryEntry> items) {
    List<HistoryEntry> normalized = normalizeHistory(items);
    writeList(projectId, HISTORY_FILE, normalized);
    return normalized;
  }

  public List<HistoryEntry> writeHistory(String projectId, String modelId, List<HistoryEntry> items) {
    List<HistoryEntry> normalized = normalizeHistory(items);
    writeModelList(projectId, modelId, HISTORY_FILE, normalized);
    return normalized;
  }

  public StoredModelInfo storeUploadedModel(String projectId, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing IFC file");
    }

    String originalFileName = StoragePathHelper.sanitizeUploadFileName(file.getOriginalFilename());
    String modelId = StoragePathHelper.buildStorageId(originalFileName);
    Path modelDir = resolveModelDir(projectId, modelId);
    Path ifcFilePath = modelDir.resolve(MODEL_FILE);
    Path manifestPath = modelDir.resolve(MODEL_MANIFEST_FILE);
    Instant now = Instant.now();

    ReentrantReadWriteLock modelFileLock = lockFor(ifcFilePath);
    modelFileLock.writeLock().lock();
    try {
      Files.createDirectories(modelDir);
      try (InputStream inputStream = file.getInputStream()) {
        StorageJsonHelper.writeBinaryAtomically(ifcFilePath, inputStream);
      }
      StoredModelManifest manifest = new StoredModelManifest(modelId, originalFileName, now, now);
      StorageJsonHelper.writeAtomically(manifestPath, manifest, objectMapper);
      StorageJsonHelper.ensureJsonArrayFileExists(modelDir.resolve(METADATA_FILE), objectMapper);
      StorageJsonHelper.ensureJsonArrayFileExists(modelDir.resolve(FURNITURE_FILE), objectMapper);
      StorageJsonHelper.ensureJsonArrayFileExists(modelDir.resolve(HISTORY_FILE), objectMapper);
      return toStoredModelInfo(manifest);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store IFC file", ex);
    } finally {
      modelFileLock.writeLock().unlock();
    }
  }

  public List<StoredModelInfo> listModels(String projectId) {
    Path modelsDir = resolveModelsDir(projectId);
    if (!Files.isDirectory(modelsDir)) {
      return new ArrayList<>();
    }

    try (var stream = Files.list(modelsDir)) {
      return stream
          .filter(Files::isDirectory)
          .map(this::readStoredModelInfoSafe)
          .filter(Objects::nonNull)
          .sorted(
              Comparator.comparing(
                      StoredModelInfo::updatedAt,
                      Comparator.nullsLast(Comparator.naturalOrder()))
                  .reversed())
          .toList();
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to list models", ex);
    }
  }

  public StoredPrefabInfo storeUploadedPrefab(String projectId, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing IFC prefab file");
    }

    String originalFileName = StoragePathHelper.sanitizeUploadFileName(file.getOriginalFilename());
    String prefabId = StoragePathHelper.buildStorageId(originalFileName);
    Path prefabDir = resolvePrefabDir(projectId, prefabId);
    Path ifcFilePath = prefabDir.resolve(PREFAB_FILE);
    Path manifestPath = prefabDir.resolve(PREFAB_MANIFEST_FILE);
    Instant now = Instant.now();

    ReentrantReadWriteLock prefabFileLock = lockFor(ifcFilePath);
    prefabFileLock.writeLock().lock();
    try {
      Files.createDirectories(prefabDir);
      try (InputStream inputStream = file.getInputStream()) {
        StorageJsonHelper.writeBinaryAtomically(ifcFilePath, inputStream);
      }
      StoredPrefabManifest manifest = new StoredPrefabManifest(prefabId, originalFileName, now, now);
      StorageJsonHelper.writeAtomically(manifestPath, manifest, objectMapper);
      return toStoredPrefabInfo(manifest);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store prefab IFC file", ex);
    } finally {
      prefabFileLock.writeLock().unlock();
    }
  }

  public List<StoredPrefabInfo> listPrefabs(String projectId) {
    Path prefabsDir = resolvePrefabsDir(projectId);
    if (!Files.isDirectory(prefabsDir)) {
      return new ArrayList<>();
    }

    try (var stream = Files.list(prefabsDir)) {
      return stream
          .filter(Files::isDirectory)
          .map(this::readStoredPrefabInfoSafe)
          .filter(Objects::nonNull)
          .sorted(
              Comparator.comparing(
                      StoredPrefabInfo::updatedAt,
                      Comparator.nullsLast(Comparator.naturalOrder()))
                  .reversed())
          .toList();
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to list prefabs", ex);
    }
  }

  public Path getModelIfcPath(String projectId, String modelId) {
    Path ifcFilePath = resolveModelDir(projectId, modelId).resolve(MODEL_FILE);
    if (!Files.isRegularFile(ifcFilePath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "IFC model file not found");
    }
    return ifcFilePath;
  }

  public Path getPrefabIfcPath(String projectId, String prefabId) {
    Path ifcFilePath = resolvePrefabDir(projectId, prefabId).resolve(PREFAB_FILE);
    if (!Files.isRegularFile(ifcFilePath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prefab IFC file not found");
    }
    return ifcFilePath;
  }

  public StoredModelInfo getModelInfo(String projectId, String modelId) {
    Path modelDir = resolveModelDir(projectId, modelId);
    if (!Files.isDirectory(modelDir)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found");
    }
    StoredModelInfo info = readStoredModelInfoSafe(modelDir);
    if (info == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model metadata not found");
    }
    return info;
  }

  public StoredModelInfo replaceModelIfc(String projectId, String modelId, Path sourceIfcPath) {
    if (sourceIfcPath == null || !Files.isRegularFile(sourceIfcPath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exported IFC file not found");
    }

    Path targetIfcPath = resolveModelDir(projectId, modelId).resolve(MODEL_FILE);
    ReentrantReadWriteLock modelFileLock = lockFor(targetIfcPath);
    modelFileLock.writeLock().lock();
    try (InputStream inputStream = Files.newInputStream(sourceIfcPath)) {
      Files.createDirectories(targetIfcPath.getParent());
      StorageJsonHelper.writeBinaryAtomically(targetIfcPath, inputStream);
      touchModelManifest(projectId, modelId);
      return getModelInfo(projectId, modelId);
    } catch (IOException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to replace stored IFC file", ex);
    } finally {
      modelFileLock.writeLock().unlock();
    }
  }

  public void resetModelState(String projectId, String modelId) {
    Path metadataPath = resolveModelFile(projectId, modelId, METADATA_FILE);
    Path furniturePath = resolveModelFile(projectId, modelId, FURNITURE_FILE);
    Path historyPath = resolveModelFile(projectId, modelId, HISTORY_FILE);
    writeList(metadataPath, new ArrayList<MetadataEntry>());
    writeList(furniturePath, new ArrayList<FurnitureItem>());
    writeList(historyPath, new ArrayList<HistoryEntry>());
    touchModelManifest(projectId, modelId);
  }

  public void deleteIfExists(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      log.warn("Failed to delete temporary file {}", path, ex);
    }
  }

  public StoredPrefabInfo getPrefabInfo(String projectId, String prefabId) {
    Path prefabDir = resolvePrefabDir(projectId, prefabId);
    if (!Files.isDirectory(prefabDir)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prefab not found");
    }
    StoredPrefabInfo info = readStoredPrefabInfoSafe(prefabDir);
    if (info == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prefab metadata not found");
    }
    return info;
  }

  public void deleteModel(String projectId, String modelId) {
    Path modelDir = resolveModelDir(projectId, modelId);
    if (!Files.isDirectory(modelDir)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found");
    }
    deleteRecursively(modelDir, "model");
  }

  public void deletePrefab(String projectId, String prefabId) {
    Path prefabDir = resolvePrefabDir(projectId, prefabId);
    if (!Files.isDirectory(prefabDir)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prefab not found");
    }
    deleteRecursively(prefabDir, "prefab");
  }

  public Path createModelExportIfcPath(String projectId, String modelId, String exportKind) {
    Path modelDir = resolveModelDir(projectId, modelId);
    if (!Files.isDirectory(modelDir)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found");
    }
    String sanitizedKind =
        (exportKind == null || exportKind.isBlank())
            ? "ifc-export"
            : exportKind.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("(^-+|-+$)", "");
    if (sanitizedKind.isBlank()) {
      sanitizedKind = "ifc-export";
    }
    String fileName = sanitizedKind + "-" + Instant.now().toEpochMilli() + ".ifc";
    Path exportsDir = modelDir.resolve(MODEL_EXPORTS_DIR).normalize();
    Path exportPath = exportsDir.resolve(fileName).normalize();
    if (!exportPath.startsWith(exportsDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export path");
    }
    try {
      Files.createDirectories(exportsDir);
    } catch (IOException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create model export directory", ex);
    }
    return exportPath;
  }

  public Path getModelExportIfcPath(String projectId, String modelId, String exportFileName) {
    if (exportFileName == null
        || exportFileName.isBlank()
        || !MODEL_EXPORT_FILE_PATTERN.matcher(exportFileName).matches()
        || !exportFileName.toLowerCase(Locale.ROOT).endsWith(".ifc")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export fileName");
    }
    Path modelDir = resolveModelDir(projectId, modelId);
    if (!Files.isDirectory(modelDir)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found");
    }
    Path exportsDir = modelDir.resolve(MODEL_EXPORTS_DIR).normalize();
    Path exportPath = exportsDir.resolve(exportFileName).normalize();
    if (!exportPath.startsWith(exportsDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export fileName");
    }
    if (!Files.isRegularFile(exportPath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exported IFC file not found");
    }
    return exportPath;
  }

  // Generic JSON list reader with locking.
  private <T> List<T> readList(String projectId, String fileName, TypeReference<List<T>> type) {
    Path filePath = resolveProjectFile(projectId, fileName);
    return readList(filePath, type);
  }

  // Generic JSON list writer with atomic file replace.
  private <T> void writeList(String projectId, String fileName, List<T> items) {
    Path filePath = resolveProjectFile(projectId, fileName);
    writeList(filePath, items);
  }

  private <T> List<T> readModelList(String projectId, String modelId, String fileName, TypeReference<List<T>> type) {
    return readList(resolveModelFile(projectId, modelId, fileName), type);
  }

  private <T> void writeModelList(String projectId, String modelId, String fileName, List<T> items) {
    Path filePath = resolveModelFile(projectId, modelId, fileName);
    writeList(filePath, items);
    touchModelManifest(projectId, modelId);
  }

  private <T> List<T> readList(Path filePath, TypeReference<List<T>> type) {
    try {
      return StorageJsonHelper.readList(filePath, type, objectMapper, locks);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read data", ex);
    }
  }

  private <T> void writeList(Path filePath, List<T> items) {
    try {
      StorageJsonHelper.writeList(filePath, items, objectMapper, locks, log);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write data", ex);
    }
  }

  // Ensure projectId is safe and resolve to a file path inside baseDir.
  private Path resolveProjectFile(String projectId, String fileName) {
    return StoragePathHelper.resolveProjectFile(baseDir, PROJECT_ID_PATTERN, projectId, fileName);
  }

  private Path resolveProjectDir(String projectId) {
    return StoragePathHelper.resolveProjectDir(baseDir, PROJECT_ID_PATTERN, projectId);
  }

  private Path resolveModelsDir(String projectId) {
    return StoragePathHelper.resolveModelsDir(baseDir, PROJECT_ID_PATTERN, projectId, MODELS_DIR);
  }

  private Path resolvePrefabsDir(String projectId) {
    return StoragePathHelper.resolvePrefabsDir(baseDir, PROJECT_ID_PATTERN, projectId, PREFABS_DIR);
  }

  private Path resolveModelDir(String projectId, String modelId) {
    return StoragePathHelper.resolveModelDir(
        baseDir, PROJECT_ID_PATTERN, MODEL_ID_PATTERN, projectId, modelId, MODELS_DIR);
  }

  private Path resolvePrefabDir(String projectId, String prefabId) {
    return StoragePathHelper.resolvePrefabDir(
        baseDir, PROJECT_ID_PATTERN, PREFAB_ID_PATTERN, projectId, prefabId, PREFABS_DIR);
  }

  private Path resolveModelFile(String projectId, String modelId, String fileName) {
    return StoragePathHelper.resolveModelFile(
        baseDir, PROJECT_ID_PATTERN, MODEL_ID_PATTERN, projectId, modelId, MODELS_DIR, fileName);
  }

  // One read/write lock per file path.
  private ReentrantReadWriteLock lockFor(Path path) {
    return StorageJsonHelper.lockFor(locks, path);
  }

  private void touchModelManifest(String projectId, String modelId) {
    Path manifestPath = resolveModelDir(projectId, modelId).resolve(MODEL_MANIFEST_FILE);
    ReentrantReadWriteLock lock = lockFor(manifestPath);
    lock.writeLock().lock();
    try {
      if (!Files.exists(manifestPath)) {
        Files.createDirectories(manifestPath.getParent());
        Instant now = Instant.now();
        StoredModelManifest manifest =
            new StoredModelManifest(modelId, modelId + ".ifc", now, now);
        StorageJsonHelper.writeAtomically(manifestPath, manifest, objectMapper);
        return;
      }
      StoredModelManifest existing = objectMapper.readValue(manifestPath.toFile(), StoredModelManifest.class);
      Instant createdAt = existing.createdAt() != null ? existing.createdAt() : Instant.now();
      String fileName = existing.fileName() != null ? existing.fileName() : modelId + ".ifc";
      StoredModelManifest updated =
          new StoredModelManifest(existing.modelId() != null ? existing.modelId() : modelId, fileName, createdAt, Instant.now());
      StorageJsonHelper.writeAtomically(manifestPath, updated, objectMapper);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update model manifest", ex);
    } finally {
      lock.writeLock().unlock();
    }
  }

  private StoredModelInfo readStoredModelInfoSafe(Path modelDir) {
    try {
      Path manifestPath = modelDir.resolve(MODEL_MANIFEST_FILE);
      StoredModelManifest manifest;
      if (Files.isRegularFile(manifestPath)) {
        manifest = objectMapper.readValue(manifestPath.toFile(), StoredModelManifest.class);
      } else {
        FileTime fileTime =
            Files.exists(modelDir) ? Files.getLastModifiedTime(modelDir) : FileTime.from(Instant.EPOCH);
        Instant fallbackTime = fileTime.toInstant();
        manifest =
            new StoredModelManifest(
                modelDir.getFileName().toString(),
                modelDir.getFileName().toString() + ".ifc",
                fallbackTime,
                fallbackTime);
      }
      return toStoredModelInfo(manifest);
    } catch (IOException ex) {
      log.warn("Skipping unreadable model directory {}", modelDir, ex);
      return null;
    }
  }

  private StoredModelInfo toStoredModelInfo(StoredModelManifest manifest) {
    Instant createdAt = manifest.createdAt() != null ? manifest.createdAt() : Instant.now();
    Instant updatedAt = manifest.updatedAt() != null ? manifest.updatedAt() : createdAt;
    String modelId = manifest.modelId() != null ? manifest.modelId() : "unknown";
    String fileName = manifest.fileName() != null ? manifest.fileName() : modelId + ".ifc";
    return new StoredModelInfo(modelId, fileName, createdAt, updatedAt);
  }

  private StoredPrefabInfo toStoredPrefabInfo(StoredPrefabManifest manifest) {
    Instant createdAt = manifest.createdAt() != null ? manifest.createdAt() : Instant.now();
    Instant updatedAt = manifest.updatedAt() != null ? manifest.updatedAt() : createdAt;
    String prefabId = manifest.prefabId() != null ? manifest.prefabId() : "unknown";
    String fileName = manifest.fileName() != null ? manifest.fileName() : prefabId + ".ifc";
    return new StoredPrefabInfo(prefabId, fileName, createdAt, updatedAt);
  }

  private StoredPrefabInfo readStoredPrefabInfoSafe(Path prefabDir) {
    try {
      Path manifestPath = prefabDir.resolve(PREFAB_MANIFEST_FILE);
      StoredPrefabManifest manifest;
      if (Files.isRegularFile(manifestPath)) {
        manifest = objectMapper.readValue(manifestPath.toFile(), StoredPrefabManifest.class);
      } else {
        FileTime fileTime =
            Files.exists(prefabDir) ? Files.getLastModifiedTime(prefabDir) : FileTime.from(Instant.EPOCH);
        Instant fallbackTime = fileTime.toInstant();
        manifest =
            new StoredPrefabManifest(
                prefabDir.getFileName().toString(),
                prefabDir.getFileName().toString() + ".ifc",
                fallbackTime,
                fallbackTime);
      }
      return toStoredPrefabInfo(manifest);
    } catch (IOException ex) {
      log.warn("Skipping unreadable prefab directory {}", prefabDir, ex);
      return null;
    }
  }

  private void deleteRecursively(Path targetDir, String label) {
    try (var walk = Files.walk(targetDir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ex) {
                  throw new RuntimeException(ex);
                }
              });
    } catch (RuntimeException ex) {
      if (ex.getCause() instanceof IOException ioEx) {
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete " + label, ioEx);
      }
      throw ex;
    } catch (IOException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete " + label, ex);
    }
  }

  // Normalize metadata list and apply server-side timestamp.
  private List<MetadataEntry> normalizeMetadata(List<MetadataEntry> items) {
    Instant now = Instant.now();
    List<MetadataEntry> normalized = new ArrayList<>();
    if (items == null) {
      return normalized;
    }
    for (MetadataEntry item : items) {
      if (item == null) {
        continue;
      }
      item.setUpdatedAt(now);
      normalized.add(item);
    }
    return normalized;
  }

  // Normalize furniture list and apply server-side timestamp.
  private List<FurnitureItem> normalizeFurniture(List<FurnitureItem> items) {
    Instant now = Instant.now();
    List<FurnitureItem> normalized = new ArrayList<>();
    if (items == null) {
      return normalized;
    }
    for (FurnitureItem item : items) {
      if (item == null) {
        continue;
      }
      item.setUpdatedAt(now);
      normalized.add(item);
    }
    return normalized;
  }

  // Normalize history list and apply server-side timestamp when missing.
  private List<HistoryEntry> normalizeHistory(List<HistoryEntry> items) {
    Instant now = Instant.now();
    List<HistoryEntry> normalized = new ArrayList<>();
    if (items == null) {
      return normalized;
    }
    for (HistoryEntry item : items) {
      if (item == null || item.getIfcId() == null || item.getLabel() == null) {
        continue;
      }
      if (item.getTimestamp() == null) {
        item.setTimestamp(now);
      }
      normalized.add(item);
    }
    return normalized;
  }
}

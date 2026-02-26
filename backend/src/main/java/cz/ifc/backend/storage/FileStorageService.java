package cz.ifc.backend.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
  private static final Pattern MODEL_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

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

    String originalFileName = sanitizeUploadFileName(file.getOriginalFilename());
    String modelId = buildModelId(originalFileName);
    Path modelDir = resolveModelDir(projectId, modelId);
    Path ifcFilePath = modelDir.resolve(MODEL_FILE);
    Path manifestPath = modelDir.resolve(MODEL_MANIFEST_FILE);
    Instant now = Instant.now();

    ReentrantReadWriteLock modelFileLock = lockFor(ifcFilePath);
    modelFileLock.writeLock().lock();
    try {
      Files.createDirectories(modelDir);
      try (InputStream inputStream = file.getInputStream()) {
        writeBinaryAtomically(ifcFilePath, inputStream);
      }
      StoredModelManifest manifest = new StoredModelManifest(modelId, originalFileName, now, now);
      writeAtomically(manifestPath, manifest);
      ensureModelJsonFileExists(modelDir.resolve(METADATA_FILE));
      ensureModelJsonFileExists(modelDir.resolve(FURNITURE_FILE));
      ensureModelJsonFileExists(modelDir.resolve(HISTORY_FILE));
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

  public Path getModelIfcPath(String projectId, String modelId) {
    Path ifcFilePath = resolveModelDir(projectId, modelId).resolve(MODEL_FILE);
    if (!Files.isRegularFile(ifcFilePath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "IFC model file not found");
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
    ReentrantReadWriteLock lock = lockFor(filePath);
    lock.readLock().lock();
    try {
      if (!Files.exists(filePath)) {
        return new ArrayList<>();
      }
      try (InputStream inputStream = Files.newInputStream(filePath)) {
        return objectMapper.readValue(inputStream, type);
      }
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read data", ex);
    } finally {
      lock.readLock().unlock();
    }
  }

  private <T> void writeList(Path filePath, List<T> items) {
    ReentrantReadWriteLock lock = lockFor(filePath);
    lock.writeLock().lock();
    try {
      log.info("Saving {} items to {}", items.size(), filePath);
      Files.createDirectories(filePath.getParent());
      writeAtomically(filePath, items);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write data", ex);
    } finally {
      lock.writeLock().unlock();
    }
  }

  // Ensure projectId is safe and resolve to a file path inside baseDir.
  private Path resolveProjectFile(String projectId, String fileName) {
    return resolveProjectDir(projectId).resolve(fileName);
  }

  private Path resolveProjectDir(String projectId) {
    if (!PROJECT_ID_PATTERN.matcher(projectId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid projectId");
    }
    Path projectDir = baseDir.resolve(projectId).normalize();
    if (!projectDir.startsWith(baseDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid projectId");
    }
    return projectDir;
  }

  private Path resolveModelsDir(String projectId) {
    return resolveProjectDir(projectId).resolve(MODELS_DIR);
  }

  private Path resolveModelDir(String projectId, String modelId) {
    if (!MODEL_ID_PATTERN.matcher(modelId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid modelId");
    }
    Path modelsDir = resolveModelsDir(projectId);
    Path modelDir = modelsDir.resolve(modelId).normalize();
    if (!modelDir.startsWith(modelsDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid modelId");
    }
    return modelDir;
  }

  private Path resolveModelFile(String projectId, String modelId, String fileName) {
    return resolveModelDir(projectId, modelId).resolve(fileName);
  }

  // One read/write lock per file path.
  private ReentrantReadWriteLock lockFor(Path path) {
    return locks.computeIfAbsent(path, ignored -> new ReentrantReadWriteLock());
  }

  // Write through a temp file and move to keep partial writes from corrupting the JSON.
  private void writeAtomically(Path targetFile, Object value) throws IOException {
    Path tempFile = Files.createTempFile(targetFile.getParent(), targetFile.getFileName().toString(), ".tmp");
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
    try {
      Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ex) {
      Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void writeBinaryAtomically(Path targetFile, InputStream inputStream) throws IOException {
    Path tempFile = Files.createTempFile(targetFile.getParent(), targetFile.getFileName().toString(), ".tmp");
    try {
      Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ex) {
      Files.deleteIfExists(tempFile);
      throw ex;
    }
  }

  private void ensureModelJsonFileExists(Path filePath) throws IOException {
    if (Files.exists(filePath)) {
      return;
    }
    writeAtomically(filePath, new ArrayList<>());
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
        writeAtomically(manifestPath, manifest);
        return;
      }
      StoredModelManifest existing = objectMapper.readValue(manifestPath.toFile(), StoredModelManifest.class);
      Instant createdAt = existing.createdAt() != null ? existing.createdAt() : Instant.now();
      String fileName = existing.fileName() != null ? existing.fileName() : modelId + ".ifc";
      StoredModelManifest updated =
          new StoredModelManifest(existing.modelId() != null ? existing.modelId() : modelId, fileName, createdAt, Instant.now());
      writeAtomically(manifestPath, updated);
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

  private String sanitizeUploadFileName(String originalFileName) {
    if (originalFileName == null || originalFileName.isBlank()) {
      return "model.ifc";
    }
    String baseName = originalFileName.replace('\\', '/');
    int separatorIndex = baseName.lastIndexOf('/');
    if (separatorIndex >= 0 && separatorIndex < baseName.length() - 1) {
      baseName = baseName.substring(separatorIndex + 1);
    }
    String cleaned = baseName.trim();
    if (cleaned.isEmpty()) {
      return "model.ifc";
    }
    return cleaned;
  }

  private String buildModelId(String originalFileName) {
    String stem = originalFileName;
    int dotIndex = stem.lastIndexOf('.');
    if (dotIndex > 0) {
      stem = stem.substring(0, dotIndex);
    }
    String slug = stem.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("(^-+|-+$)", "");
    if (slug.isBlank()) {
      slug = "model";
    }
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    return slug + "-" + suffix;
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

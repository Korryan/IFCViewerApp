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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

@Service
public class FileStorageService {
  private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
  private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final String METADATA_FILE = "metadata.json";
  private static final String FURNITURE_FILE = "furniture.json";
  private static final String HISTORY_FILE = "history.json";

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

  // Read project metadata list from disk.
  public List<MetadataEntry> readMetadata(String projectId) {
    return readList(projectId, METADATA_FILE, new TypeReference<List<MetadataEntry>>() {});
  }

  // Write project metadata list to disk (overwrites previous file).
  public List<MetadataEntry> writeMetadata(String projectId, List<MetadataEntry> items) {
    List<MetadataEntry> normalized = normalizeMetadata(items);
    writeList(projectId, METADATA_FILE, normalized);
    return normalized;
  }

  // Read project furniture list from disk.
  public List<FurnitureItem> readFurniture(String projectId) {
    return readList(projectId, FURNITURE_FILE, new TypeReference<List<FurnitureItem>>() {});
  }

  // Write project furniture list to disk (overwrites previous file).
  public List<FurnitureItem> writeFurniture(String projectId, List<FurnitureItem> items) {
    List<FurnitureItem> normalized = normalizeFurniture(items);
    writeList(projectId, FURNITURE_FILE, normalized);
    return normalized;
  }

  // Read project change history list from disk.
  public List<HistoryEntry> readHistory(String projectId) {
    return readList(projectId, HISTORY_FILE, new TypeReference<List<HistoryEntry>>() {});
  }

  // Write project change history list to disk (overwrites previous file).
  public List<HistoryEntry> writeHistory(String projectId, List<HistoryEntry> items) {
    List<HistoryEntry> normalized = normalizeHistory(items);
    writeList(projectId, HISTORY_FILE, normalized);
    return normalized;
  }

  // Generic JSON list reader with locking.
  private <T> List<T> readList(String projectId, String fileName, TypeReference<List<T>> type) {
    Path filePath = resolveProjectFile(projectId, fileName);
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

  // Generic JSON list writer with atomic file replace.
  private <T> void writeList(String projectId, String fileName, List<T> items) {
    Path filePath = resolveProjectFile(projectId, fileName);
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
    if (!PROJECT_ID_PATTERN.matcher(projectId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid projectId");
    }
    Path projectDir = baseDir.resolve(projectId).normalize();
    if (!projectDir.startsWith(baseDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid projectId");
    }
    return projectDir.resolve(fileName);
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

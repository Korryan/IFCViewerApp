package cz.ifc.backend.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;

final class StorageJsonHelper {
  private StorageJsonHelper() {}

  // Returns the per-file read/write lock used to serialize access to one storage path.
  static ReentrantReadWriteLock lockFor(
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks, Path path) {
    return locks.computeIfAbsent(path, ignored -> new ReentrantReadWriteLock());
  }

  // Reads a JSON list file under a read lock and returns an empty list when the file does not exist.
  static <T> List<T> readList(
      Path filePath,
      TypeReference<List<T>> type,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks)
      throws IOException {
    ReentrantReadWriteLock lock = lockFor(locks, filePath);
    lock.readLock().lock();
    try {
      if (!Files.exists(filePath)) {
        return new ArrayList<>();
      }
      try (InputStream inputStream = Files.newInputStream(filePath)) {
        return objectMapper.readValue(inputStream, type);
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  // Writes a JSON list file under a write lock using an atomic temp-file replacement strategy.
  static <T> void writeList(
      Path filePath,
      List<T> items,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      Logger log)
      throws IOException {
    ReentrantReadWriteLock lock = lockFor(locks, filePath);
    lock.writeLock().lock();
    try {
      log.info("Saving {} items to {}", items.size(), filePath);
      Files.createDirectories(filePath.getParent());
      writeAtomically(filePath, items, objectMapper);
    } finally {
      lock.writeLock().unlock();
    }
  }

  // Writes one JSON value through a temp file and swaps it into place to avoid partial writes.
  static void writeAtomically(Path targetFile, Object value, ObjectMapper objectMapper)
      throws IOException {
    Path tempFile =
        Files.createTempFile(targetFile.getParent(), targetFile.getFileName().toString(), ".tmp");
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
    try {
      Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ex) {
      Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // Writes one binary file through a temp file and swaps it into place to avoid partial writes.
  static void writeBinaryAtomically(Path targetFile, InputStream inputStream) throws IOException {
    Path tempFile =
        Files.createTempFile(targetFile.getParent(), targetFile.getFileName().toString(), ".tmp");
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

  // Ensures an empty JSON array file exists at the target path for model-scoped state files.
  static void ensureJsonArrayFileExists(Path filePath, ObjectMapper objectMapper) throws IOException {
    if (Files.exists(filePath)) {
      return;
    }
    writeAtomically(filePath, new ArrayList<>(), objectMapper);
  }
}

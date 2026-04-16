package cz.ifc.backend.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class StorageProjectStateHelper {
  // Prevents instantiation of this project state helper.
  private StorageProjectStateHelper() {}

  // Reads a project-scoped JSON list from a pre-resolved storage file path.
  static <T> List<T> readProjectList(
      Path filePath,
      TypeReference<List<T>> type,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks) {
    try {
      return StorageJsonHelper.readList(filePath, type, objectMapper, locks);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read data", ex);
    }
  }

  // Writes a project-scoped JSON list to a pre-resolved storage file path.
  static <T> void writeProjectList(
      Path filePath,
      List<T> items,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      Logger log) {
    try {
      StorageJsonHelper.writeList(filePath, items, objectMapper, locks, log);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write data", ex);
    }
  }
}

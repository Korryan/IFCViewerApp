package cz.ifc.backend.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class StorageManifestHelper {
  private StorageManifestHelper() {}

  // Updates the model manifest timestamp while preserving any previously stored manifest metadata.
  static void touchModelManifest(
      Path manifestPath,
      String modelId,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks) {
    ReentrantReadWriteLock lock = StorageJsonHelper.lockFor(locks, manifestPath);
    lock.writeLock().lock();
    try {
      Files.createDirectories(manifestPath.getParent());
      FileStorageService.StoredModelManifest existing =
          Files.isRegularFile(manifestPath)
              ? objectMapper.readValue(manifestPath.toFile(), FileStorageService.StoredModelManifest.class)
              : null;
      Instant now = Instant.now();
      Instant createdAt = existing != null && existing.createdAt() != null ? existing.createdAt() : now;
      String fileName = existing != null && existing.fileName() != null ? existing.fileName() : modelId + ".ifc";
      String persistedModelId = existing != null && existing.modelId() != null ? existing.modelId() : modelId;
      FileStorageService.StoredModelManifest updated =
          new FileStorageService.StoredModelManifest(persistedModelId, fileName, createdAt, now);
      StorageJsonHelper.writeAtomically(manifestPath, updated, objectMapper);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update model manifest", ex);
    } finally {
      lock.writeLock().unlock();
    }
  }

  // Reads model manifest info and falls back to directory timestamps when the manifest file is missing.
  static FileStorageService.StoredModelInfo readStoredModelInfoSafe(
      Path modelDir,
      String manifestFileName,
      ObjectMapper objectMapper,
      Logger log) {
    try {
      return toStoredModelInfo(readModelManifest(modelDir, manifestFileName, objectMapper));
    } catch (IOException ex) {
      log.warn("Skipping unreadable model directory {}", modelDir, ex);
      return null;
    }
  }

  // Converts a stored model manifest into the public DTO returned by the storage service.
  static FileStorageService.StoredModelInfo toStoredModelInfo(
      FileStorageService.StoredModelManifest manifest) {
    Instant createdAt = manifest.createdAt() != null ? manifest.createdAt() : Instant.now();
    Instant updatedAt = manifest.updatedAt() != null ? manifest.updatedAt() : createdAt;
    String modelId = manifest.modelId() != null ? manifest.modelId() : "unknown";
    String fileName = manifest.fileName() != null ? manifest.fileName() : modelId + ".ifc";
    return new FileStorageService.StoredModelInfo(modelId, fileName, createdAt, updatedAt);
  }

  // Reads prefab manifest info and falls back to directory timestamps when the manifest file is missing.
  static FileStorageService.StoredPrefabInfo readStoredPrefabInfoSafe(
      Path prefabDir,
      String manifestFileName,
      ObjectMapper objectMapper,
      Logger log) {
    try {
      return toStoredPrefabInfo(readPrefabManifest(prefabDir, manifestFileName, objectMapper));
    } catch (IOException ex) {
      log.warn("Skipping unreadable prefab directory {}", prefabDir, ex);
      return null;
    }
  }

  // Converts a stored prefab manifest into the public DTO returned by the storage service.
  static FileStorageService.StoredPrefabInfo toStoredPrefabInfo(
      FileStorageService.StoredPrefabManifest manifest) {
    Instant createdAt = manifest.createdAt() != null ? manifest.createdAt() : Instant.now();
    Instant updatedAt = manifest.updatedAt() != null ? manifest.updatedAt() : createdAt;
    String prefabId = manifest.prefabId() != null ? manifest.prefabId() : "unknown";
    String fileName = manifest.fileName() != null ? manifest.fileName() : prefabId + ".ifc";
    return new FileStorageService.StoredPrefabInfo(prefabId, fileName, createdAt, updatedAt);
  }

  // Reads a model manifest or synthesizes a fallback manifest from the directory name and timestamps.
  private static FileStorageService.StoredModelManifest readModelManifest(
      Path modelDir,
      String manifestFileName,
      ObjectMapper objectMapper)
      throws IOException {
    Path manifestPath = modelDir.resolve(manifestFileName);
    if (Files.isRegularFile(manifestPath)) {
      return objectMapper.readValue(manifestPath.toFile(), FileStorageService.StoredModelManifest.class);
    }
    Instant fallbackTime = resolveFallbackTime(modelDir);
    String modelId = modelDir.getFileName().toString();
    return new FileStorageService.StoredModelManifest(
        modelId,
        modelId + ".ifc",
        fallbackTime,
        fallbackTime);
  }

  // Reads a prefab manifest or synthesizes a fallback manifest from the directory name and timestamps.
  private static FileStorageService.StoredPrefabManifest readPrefabManifest(
      Path prefabDir,
      String manifestFileName,
      ObjectMapper objectMapper)
      throws IOException {
    Path manifestPath = prefabDir.resolve(manifestFileName);
    if (Files.isRegularFile(manifestPath)) {
      return objectMapper.readValue(manifestPath.toFile(), FileStorageService.StoredPrefabManifest.class);
    }
    Instant fallbackTime = resolveFallbackTime(prefabDir);
    String prefabId = prefabDir.getFileName().toString();
    return new FileStorageService.StoredPrefabManifest(
        prefabId,
        prefabId + ".ifc",
        fallbackTime,
        fallbackTime);
  }

  // Returns the directory modification time used when manifest timestamps are unavailable.
  private static Instant resolveFallbackTime(Path directory) throws IOException {
    FileTime fileTime = Files.exists(directory) ? Files.getLastModifiedTime(directory) : FileTime.from(Instant.EPOCH);
    return fileTime.toInstant();
  }
}

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

final class StorageModelStateHelper {
  // Prevents instantiation of this model state helper.
  private StorageModelStateHelper() {}

  // Ensures that a newly stored model has the JSON state files required by the editor.
  static void ensureModelStateFiles(
      Path modelDir,
      String metadataFileName,
      String furnitureFileName,
      String historyFileName,
      ObjectMapper objectMapper)
      throws IOException {
    StorageJsonHelper.ensureJsonArrayFileExists(modelDir.resolve(metadataFileName), objectMapper);
    StorageJsonHelper.ensureJsonArrayFileExists(modelDir.resolve(furnitureFileName), objectMapper);
    StorageJsonHelper.ensureJsonArrayFileExists(modelDir.resolve(historyFileName), objectMapper);
  }

  // Reads a model-scoped JSON list using the shared storage locks and mapper.
  static <T> List<T> readModelList(
      Path modelDir,
      String fileName,
      TypeReference<List<T>> type,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks) {
    try {
      return StorageJsonHelper.readList(modelDir.resolve(fileName), type, objectMapper, locks);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read data", ex);
    }
  }

  // Writes a model-scoped JSON list and refreshes the manifest timestamp for that model.
  static <T> void writeModelList(
      Path modelDir,
      String modelId,
      String fileName,
      Path manifestFilePath,
      List<T> items,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      org.slf4j.Logger log) {
    writeList(modelDir.resolve(fileName), items, objectMapper, locks, log);
    touchModelManifest(manifestFilePath, modelId, objectMapper, locks);
  }

  // Replaces the stored model IFC binary and refreshes the manifest timestamp afterwards.
  static void replaceModelIfc(
      Path modelDir,
      String modelId,
      Path ifcFilePath,
      Path manifestFilePath,
      Path sourceIfcPath,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      String missingSourceMessage,
      String failureMessage) {
    StorageCatalogHelper.replaceStoredIfcFile(
        sourceIfcPath,
        ifcFilePath,
        locks,
        missingSourceMessage,
        failureMessage);
    touchModelManifest(manifestFilePath, modelId, objectMapper, locks);
  }

  // Resets all model-scoped editor state files back to empty lists and refreshes the manifest timestamp once.
  static void resetModelState(
      Path modelDir,
      String modelId,
      Path manifestFilePath,
      String metadataFileName,
      String furnitureFileName,
      String historyFileName,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      Logger log) {
    writeList(modelDir.resolve(metadataFileName), List.of(), objectMapper, locks, log);
    writeList(modelDir.resolve(furnitureFileName), List.of(), objectMapper, locks, log);
    writeList(modelDir.resolve(historyFileName), List.of(), objectMapper, locks, log);
    touchModelManifest(manifestFilePath, modelId, objectMapper, locks);
  }

  // Writes a model-scoped JSON list through the shared storage JSON helper.
  private static <T> void writeList(
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

  // Refreshes the model manifest timestamp by delegating to the shared manifest helper.
  private static void touchModelManifest(
      Path manifestFilePath,
      String modelId,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks) {
    StorageManifestHelper.touchModelManifest(manifestFilePath, modelId, objectMapper, locks);
  }
}

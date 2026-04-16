package cz.ifc.backend.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;

final class StorageProjectStateFacade {
  private final StorageCatalogPaths storagePaths;
  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<Path, ReentrantReadWriteLock> locks;
  private final Logger log;
  private final String metadataFileName;
  private final String furnitureFileName;
  private final String historyFileName;

  // Captures the shared dependencies needed to serve project and model editor state operations.
  StorageProjectStateFacade(
      StorageCatalogPaths storagePaths,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      Logger log,
      String metadataFileName,
      String furnitureFileName,
      String historyFileName) {
    this.storagePaths = storagePaths;
    this.objectMapper = objectMapper;
    this.locks = locks;
    this.log = log;
    this.metadataFileName = metadataFileName;
    this.furnitureFileName = furnitureFileName;
    this.historyFileName = historyFileName;
  }

  // Reads project-scoped metadata state from storage.
  List<MetadataEntry> readMetadata(String projectId) {
    return readProjectState(projectId, metadataFileName, new TypeReference<List<MetadataEntry>>() {});
  }

  // Reads model-scoped metadata state from storage.
  List<MetadataEntry> readMetadata(String projectId, String modelId) {
    return readModelState(projectId, modelId, metadataFileName, new TypeReference<List<MetadataEntry>>() {});
  }

  // Normalizes and writes project-scoped metadata state back to storage.
  List<MetadataEntry> writeMetadata(String projectId, List<MetadataEntry> items) {
    return writeProjectState(projectId, metadataFileName, items, StorageStateNormalizer::normalizeMetadata);
  }

  // Normalizes and writes model-scoped metadata state back to storage.
  List<MetadataEntry> writeMetadata(String projectId, String modelId, List<MetadataEntry> items) {
    return writeModelState(projectId, modelId, metadataFileName, items, StorageStateNormalizer::normalizeMetadata);
  }

  // Reads project-scoped furniture state from storage.
  List<FurnitureItem> readFurniture(String projectId) {
    return readProjectState(projectId, furnitureFileName, new TypeReference<List<FurnitureItem>>() {});
  }

  // Reads model-scoped furniture state from storage.
  List<FurnitureItem> readFurniture(String projectId, String modelId) {
    return readModelState(projectId, modelId, furnitureFileName, new TypeReference<List<FurnitureItem>>() {});
  }

  // Normalizes and writes project-scoped furniture state back to storage.
  List<FurnitureItem> writeFurniture(String projectId, List<FurnitureItem> items) {
    return writeProjectState(projectId, furnitureFileName, items, StorageStateNormalizer::normalizeFurniture);
  }

  // Normalizes and writes model-scoped furniture state back to storage.
  List<FurnitureItem> writeFurniture(String projectId, String modelId, List<FurnitureItem> items) {
    return writeModelState(projectId, modelId, furnitureFileName, items, StorageStateNormalizer::normalizeFurniture);
  }

  // Reads project-scoped history state from storage.
  List<HistoryEntry> readHistory(String projectId) {
    return readProjectState(projectId, historyFileName, new TypeReference<List<HistoryEntry>>() {});
  }

  // Reads model-scoped history state from storage.
  List<HistoryEntry> readHistory(String projectId, String modelId) {
    return readModelState(projectId, modelId, historyFileName, new TypeReference<List<HistoryEntry>>() {});
  }

  // Normalizes and writes project-scoped history state back to storage.
  List<HistoryEntry> writeHistory(String projectId, List<HistoryEntry> items) {
    return writeProjectState(projectId, historyFileName, items, StorageStateNormalizer::normalizeHistory);
  }

  // Normalizes and writes model-scoped history state back to storage.
  List<HistoryEntry> writeHistory(String projectId, String modelId, List<HistoryEntry> items) {
    return writeModelState(projectId, modelId, historyFileName, items, StorageStateNormalizer::normalizeHistory);
  }

  // Reads one project-scoped editor state list by file name through the shared project state helper.
  private <T> List<T> readProjectState(
      String projectId,
      String fileName,
      TypeReference<List<T>> type) {
    return StorageProjectStateHelper.readProjectList(
        storagePaths.projectFile(projectId, fileName),
        type,
        objectMapper,
        locks);
  }

  // Reads one model-scoped editor state list by file name through the shared model state helper.
  private <T> List<T> readModelState(
      String projectId,
      String modelId,
      String fileName,
      TypeReference<List<T>> type) {
    return StorageModelStateHelper.readModelList(
        storagePaths.model(projectId, modelId).directory(),
        fileName,
        type,
        objectMapper,
        locks);
  }

  // Normalizes and writes one project-scoped editor state list while preserving the original return contract.
  private <T> List<T> writeProjectState(
      String projectId,
      String fileName,
      List<T> items,
      UnaryOperator<List<T>> normalizer) {
    List<T> normalized = normalizer.apply(items);
    StorageProjectStateHelper.writeProjectList(
        storagePaths.projectFile(projectId, fileName),
        normalized,
        objectMapper,
        locks,
        log);
    return normalized;
  }

  // Normalizes and writes one model-scoped editor state list while refreshing the model manifest timestamp.
  private <T> List<T> writeModelState(
      String projectId,
      String modelId,
      String fileName,
      List<T> items,
      UnaryOperator<List<T>> normalizer) {
    List<T> normalized = normalizer.apply(items);
    StorageCatalogPaths.ModelPaths modelPaths = storagePaths.model(projectId, modelId);
    StorageModelStateHelper.writeModelList(
        modelPaths.directory(),
        modelId,
        fileName,
        modelPaths.manifestFile(),
        normalized,
        objectMapper,
        locks,
        log);
    return normalized;
  }
}

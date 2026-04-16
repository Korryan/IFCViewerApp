package cz.ifc.backend.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

final class StorageCatalogHelper {
  // Allows callers to run extra filesystem initialization after a binary IFC upload succeeds.
  @FunctionalInterface
  interface IoAction {
    void run() throws IOException;
  }

  // Prevents instantiation of this catalog helper.
  private StorageCatalogHelper() {}

  // Validates an uploaded file and returns a sanitized original file name for storage IDs and manifests.
  static String requireUploadedFileName(MultipartFile file, String missingMessage) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, missingMessage);
    }
    return StoragePathHelper.sanitizeUploadFileName(file.getOriginalFilename());
  }

  // Stores an uploaded IFC binary and manifest atomically while holding the destination file write lock.
  static void storeUploadedIfcFile(
      MultipartFile file,
      Path ifcFilePath,
      Path manifestPath,
      Object manifest,
      ObjectMapper objectMapper,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      IoAction afterStore,
      String failureMessage) {
    ReentrantReadWriteLock fileLock = StorageJsonHelper.lockFor(locks, ifcFilePath);
    fileLock.writeLock().lock();
    try {
      Files.createDirectories(ifcFilePath.getParent());
      try (InputStream inputStream = file.getInputStream()) {
        StorageJsonHelper.writeBinaryAtomically(ifcFilePath, inputStream);
      }
      StorageJsonHelper.writeAtomically(manifestPath, manifest, objectMapper);
      afterStore.run();
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage, ex);
    } finally {
      fileLock.writeLock().unlock();
    }
  }

  // Lists stored entries in a directory using the provided info reader and final sort order.
  static <T> List<T> listStoredInfos(
      Path rootDir,
      Function<Path, T> infoReader,
      Comparator<? super T> comparator,
      String failureMessage) {
    if (!Files.isDirectory(rootDir)) {
      return new ArrayList<>();
    }
    try (var stream = Files.list(rootDir)) {
      return stream
          .filter(Files::isDirectory)
          .map(infoReader)
          .filter(Objects::nonNull)
          .sorted(comparator)
          .toList();
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage, ex);
    }
  }

  // Verifies that a stored directory exists before any child files or metadata are resolved from it.
  static Path requireStoredDirectory(Path directory, String missingDirectoryMessage) {
    if (!Files.isDirectory(directory)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingDirectoryMessage);
    }
    return directory;
  }

  // Resolves stored metadata for a directory and maps missing directories or unreadable manifests to API errors.
  static <T> T requireStoredInfo(
      Path directory,
      String missingDirectoryMessage,
      Function<Path, T> infoReader,
      String missingInfoMessage) {
    T info = infoReader.apply(requireStoredDirectory(directory, missingDirectoryMessage));
    if (info == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingInfoMessage);
    }
    return info;
  }

  // Resolves a child file under a stored directory and ensures both the directory and file already exist.
  static Path requireStoredChildFile(
      Path directory,
      String missingDirectoryMessage,
      String fileName,
      String missingFileMessage) {
    return requireStoredIfcPath(
        requireStoredDirectory(directory, missingDirectoryMessage).resolve(fileName),
        missingFileMessage);
  }

  // Resolves a stored IFC file path and reports missing binaries as API-level not-found errors.
  static Path requireStoredIfcPath(Path ifcFilePath, String missingFileMessage) {
    if (!Files.isRegularFile(ifcFilePath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingFileMessage);
    }
    return ifcFilePath;
  }

  // Deletes a stored directory only when it exists and delegates the recursive filesystem work to file ops.
  static void deleteStoredDirectory(
      Path directory,
      String missingDirectoryMessage,
      String label) {
    StorageFileOpsHelper.deleteRecursively(
        requireStoredDirectory(directory, missingDirectoryMessage),
        label);
  }

  // Creates a new IFC export path only when the source stored directory exists.
  static Path createStoredExportIfcPath(
      Path directory,
      String missingDirectoryMessage,
      String exportKind) {
    return StorageFileOpsHelper.createModelExportIfcPath(
        requireStoredDirectory(directory, missingDirectoryMessage),
        exportKind);
  }

  // Resolves an existing IFC export path only when the source stored directory exists.
  static Path getStoredExportIfcPath(
      Path directory,
      String missingDirectoryMessage,
      String exportFileName) {
    return StorageFileOpsHelper.getModelExportIfcPath(
        requireStoredDirectory(directory, missingDirectoryMessage),
        exportFileName);
  }

  // Replaces a stored IFC binary atomically while holding the destination file write lock.
  static void replaceStoredIfcFile(
      Path sourceIfcPath,
      Path targetIfcPath,
      ConcurrentHashMap<Path, ReentrantReadWriteLock> locks,
      String missingSourceMessage,
      String failureMessage) {
    if (sourceIfcPath == null || !Files.isRegularFile(sourceIfcPath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingSourceMessage);
    }
    ReentrantReadWriteLock fileLock = StorageJsonHelper.lockFor(locks, targetIfcPath);
    fileLock.writeLock().lock();
    try (InputStream inputStream = Files.newInputStream(sourceIfcPath)) {
      Files.createDirectories(targetIfcPath.getParent());
      StorageJsonHelper.writeBinaryAtomically(targetIfcPath, inputStream);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage, ex);
    } finally {
      fileLock.writeLock().unlock();
    }
  }
}

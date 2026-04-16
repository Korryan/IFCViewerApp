package cz.ifc.backend.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class StorageFileOpsHelper {
  private static final String MODEL_EXPORTS_DIR = "exports";
  private static final Pattern MODEL_EXPORT_FILE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

  // Prevents instantiation of this filesystem helper.
  private StorageFileOpsHelper() {}

  // Deletes a stored model or prefab directory recursively and reports storage-specific failures.
  static void deleteRecursively(Path targetDir, String label) {
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

  // Deletes a temporary file when present and only logs failures instead of surfacing them to callers.
  static void deleteIfExists(Path path, Logger log) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      log.warn("Failed to delete temporary file {}", path, ex);
    }
  }

  // Creates a unique IFC export path under the model exports directory after sanitizing the export kind.
  static Path createModelExportIfcPath(Path modelDir, String exportKind) {
    String fileName = sanitizeExportKind(exportKind) + "-" + Instant.now().toEpochMilli() + ".ifc";
    Path exportsDir = modelDir.resolve(MODEL_EXPORTS_DIR).normalize();
    Path exportPath = resolveExportPath(exportsDir, fileName, "Invalid export path");
    try {
      Files.createDirectories(exportsDir);
    } catch (IOException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create model export directory", ex);
    }
    return exportPath;
  }

  // Resolves an existing exported IFC file only when the requested file name is valid and contained in exports.
  static Path getModelExportIfcPath(Path modelDir, String exportFileName) {
    validateExportFileName(exportFileName);
    Path exportsDir = modelDir.resolve(MODEL_EXPORTS_DIR).normalize();
    Path exportPath = resolveExportPath(exportsDir, exportFileName, "Invalid export fileName");
    if (!Files.isRegularFile(exportPath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exported IFC file not found");
    }
    return exportPath;
  }

  // Normalizes export kind text into a stable slug used as the export file prefix.
  private static String sanitizeExportKind(String exportKind) {
    String sanitizedKind =
        (exportKind == null || exportKind.isBlank())
            ? "ifc-export"
            : exportKind.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("(^-+|-+$)", "");
    return sanitizedKind.isBlank() ? "ifc-export" : sanitizedKind;
  }

  // Rejects malformed export file names before they are resolved on disk.
  private static void validateExportFileName(String exportFileName) {
    if (exportFileName == null
        || exportFileName.isBlank()
        || !MODEL_EXPORT_FILE_PATTERN.matcher(exportFileName).matches()
        || !exportFileName.toLowerCase(Locale.ROOT).endsWith(".ifc")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export fileName");
    }
  }

  // Resolves an export path and verifies that it remains inside the exports directory.
  private static Path resolveExportPath(Path exportsDir, String fileName, String errorMessage) {
    Path exportPath = exportsDir.resolve(fileName).normalize();
    if (!exportPath.startsWith(exportsDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
    }
    return exportPath;
  }
}

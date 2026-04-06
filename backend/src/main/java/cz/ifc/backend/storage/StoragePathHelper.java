package cz.ifc.backend.storage;

import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class StoragePathHelper {
  private StoragePathHelper() {}

  // Resolves one project directory under the storage base after validating the project id format.
  static Path resolveProjectDir(Path baseDir, Pattern projectIdPattern, String projectId) {
    if (!projectIdPattern.matcher(projectId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid projectId");
    }
    Path projectDir = baseDir.resolve(projectId).normalize();
    if (!projectDir.startsWith(baseDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid projectId");
    }
    return projectDir;
  }

  // Resolves a file directly inside one project directory.
  static Path resolveProjectFile(
      Path baseDir, Pattern projectIdPattern, String projectId, String fileName) {
    return resolveProjectDir(baseDir, projectIdPattern, projectId).resolve(fileName);
  }

  // Resolves the models directory for one project.
  static Path resolveModelsDir(
      Path baseDir, Pattern projectIdPattern, String projectId, String modelsDirName) {
    return resolveProjectDir(baseDir, projectIdPattern, projectId).resolve(modelsDirName);
  }

  // Resolves the prefabs directory for one project.
  static Path resolvePrefabsDir(
      Path baseDir, Pattern projectIdPattern, String projectId, String prefabsDirName) {
    return resolveProjectDir(baseDir, projectIdPattern, projectId).resolve(prefabsDirName);
  }

  // Resolves one model directory under the project models directory after validating the model id.
  static Path resolveModelDir(
      Path baseDir,
      Pattern projectIdPattern,
      Pattern modelIdPattern,
      String projectId,
      String modelId,
      String modelsDirName) {
    if (!modelIdPattern.matcher(modelId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid modelId");
    }
    Path modelsDir = resolveModelsDir(baseDir, projectIdPattern, projectId, modelsDirName);
    Path modelDir = modelsDir.resolve(modelId).normalize();
    if (!modelDir.startsWith(modelsDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid modelId");
    }
    return modelDir;
  }

  // Resolves one prefab directory under the project prefabs directory after validating the prefab id.
  static Path resolvePrefabDir(
      Path baseDir,
      Pattern projectIdPattern,
      Pattern prefabIdPattern,
      String projectId,
      String prefabId,
      String prefabsDirName) {
    if (!prefabIdPattern.matcher(prefabId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid prefabId");
    }
    Path prefabsDir = resolvePrefabsDir(baseDir, projectIdPattern, projectId, prefabsDirName);
    Path prefabDir = prefabsDir.resolve(prefabId).normalize();
    if (!prefabDir.startsWith(prefabsDir)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid prefabId");
    }
    return prefabDir;
  }

  // Resolves one file inside a specific stored model directory.
  static Path resolveModelFile(
      Path baseDir,
      Pattern projectIdPattern,
      Pattern modelIdPattern,
      String projectId,
      String modelId,
      String modelsDirName,
      String fileName) {
    return resolveModelDir(baseDir, projectIdPattern, modelIdPattern, projectId, modelId, modelsDirName)
        .resolve(fileName);
  }

  // Sanitizes an uploaded file name down to its final path segment with a non-empty fallback.
  static String sanitizeUploadFileName(String originalFileName) {
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

  // Builds a storage-safe random id from the uploaded file name stem.
  static String buildStorageId(String originalFileName) {
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
}

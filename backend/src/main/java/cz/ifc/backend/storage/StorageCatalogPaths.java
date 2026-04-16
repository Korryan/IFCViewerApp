package cz.ifc.backend.storage;

import java.nio.file.Path;
import java.util.regex.Pattern;

final class StorageCatalogPaths {
  private final Path baseDir;
  private final Pattern projectIdPattern;
  private final Pattern modelIdPattern;
  private final Pattern prefabIdPattern;
  private final String modelsDirName;
  private final String modelFileName;
  private final String modelManifestFileName;
  private final String prefabsDirName;
  private final String prefabFileName;
  private final String prefabManifestFileName;

  // Captures the storage path configuration used to resolve all project, model, and prefab locations.
  StorageCatalogPaths(
      Path baseDir,
      Pattern projectIdPattern,
      Pattern modelIdPattern,
      Pattern prefabIdPattern,
      String modelsDirName,
      String modelFileName,
      String modelManifestFileName,
      String prefabsDirName,
      String prefabFileName,
      String prefabManifestFileName) {
    this.baseDir = baseDir;
    this.projectIdPattern = projectIdPattern;
    this.modelIdPattern = modelIdPattern;
    this.prefabIdPattern = prefabIdPattern;
    this.modelsDirName = modelsDirName;
    this.modelFileName = modelFileName;
    this.modelManifestFileName = modelManifestFileName;
    this.prefabsDirName = prefabsDirName;
    this.prefabFileName = prefabFileName;
    this.prefabManifestFileName = prefabManifestFileName;
  }

  // Resolves a project-scoped file under the validated project directory.
  Path projectFile(String projectId, String fileName) {
    return StoragePathHelper.resolveProjectFile(baseDir, projectIdPattern, projectId, fileName);
  }

  // Resolves the models directory for one validated project.
  Path modelsDir(String projectId) {
    return StoragePathHelper.resolveModelsDir(baseDir, projectIdPattern, projectId, modelsDirName);
  }

  // Resolves the prefabs directory for one validated project.
  Path prefabsDir(String projectId) {
    return StoragePathHelper.resolvePrefabsDir(baseDir, projectIdPattern, projectId, prefabsDirName);
  }

  // Resolves the directory plus IFC and manifest files for one stored model.
  ModelPaths model(String projectId, String modelId) {
    Path directory =
        StoragePathHelper.resolveModelDir(
            baseDir, projectIdPattern, modelIdPattern, projectId, modelId, modelsDirName);
    return new ModelPaths(
        directory,
        directory.resolve(modelFileName),
        directory.resolve(modelManifestFileName));
  }

  // Resolves the directory plus IFC and manifest files for one stored prefab.
  PrefabPaths prefab(String projectId, String prefabId) {
    Path directory =
        StoragePathHelper.resolvePrefabDir(
            baseDir, projectIdPattern, prefabIdPattern, projectId, prefabId, prefabsDirName);
    return new PrefabPaths(
        directory,
        directory.resolve(prefabFileName),
        directory.resolve(prefabManifestFileName));
  }

  // Bundles all resolved filesystem paths that belong to one stored model.
  record ModelPaths(Path directory, Path ifcFile, Path manifestFile) {}

  // Bundles all resolved filesystem paths that belong to one stored prefab.
  record PrefabPaths(Path directory, Path ifcFile, Path manifestFile) {}
}

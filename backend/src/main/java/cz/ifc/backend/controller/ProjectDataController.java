package cz.ifc.backend.controller;

import cz.ifc.backend.ifcopenshell.IfcOpenShellClient;
import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import cz.ifc.backend.storage.FileStorageService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.multipart.MultipartFile;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(path = "/projects/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectDataController {
  private static final Logger log = LoggerFactory.getLogger(ProjectDataController.class);
  private final FileStorageService storageService;
  private final IfcOpenShellClient ifcOpenShellClient;

  public ProjectDataController(
      FileStorageService storageService,
      IfcOpenShellClient ifcOpenShellClient) {
    this.storageService = storageService;
    this.ifcOpenShellClient = ifcOpenShellClient;
  }

  @GetMapping("/models")
  public List<FileStorageService.StoredModelInfo> listModels(@PathVariable String projectId) {
    return storageService.listModels(projectId);
  }

  @PostMapping(path = "/models", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public FileStorageService.StoredModelInfo uploadModel(
      @PathVariable String projectId,
      @RequestParam("file") MultipartFile file) {
    FileStorageService.StoredModelInfo stored = storageService.storeUploadedModel(projectId, file);
    // Best-effort auto-import of IFC embedded state (Pset_Baka_*) for better UX.
    try {
      Path sourceIfcPath = storageService.getModelIfcPath(projectId, stored.modelId());
      IfcOpenShellClient.ImportStateResponse imported = ifcOpenShellClient.importState(sourceIfcPath);
      storageService.writeMetadata(projectId, stored.modelId(), safeList(imported.metadata()));
      storageService.writeFurniture(projectId, stored.modelId(), safeList(imported.furniture()));
      storageService.writeHistory(projectId, stored.modelId(), safeList(imported.history()));
      if (!safeList(imported.warnings()).isEmpty()) {
        log.info(
            "IFC state auto-import warnings for model {}: {}",
            stored.modelId(),
            imported.warnings());
      }
    } catch (Exception ex) { // Best-effort hydration must not block upload.
      log.warn("IFC state auto-import failed for model {}", stored.modelId(), ex);
    }
    return stored;
  }

  @GetMapping(path = "/models/{modelId}/ifc", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<Resource> downloadIfcModel(
      @PathVariable String projectId,
      @PathVariable String modelId) {
    FileStorageService.StoredModelInfo modelInfo = storageService.getModelInfo(projectId, modelId);
    Resource resource = new FileSystemResource(storageService.getModelIfcPath(projectId, modelId));
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(modelInfo.fileName()).build().toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(resource);
  }

  @PostMapping("/models/{modelId}/ifc/import-state")
  public IfcImportStateResponse importIfcState(
      @PathVariable String projectId,
      @PathVariable String modelId) {
    Path sourceIfcPath = storageService.getModelIfcPath(projectId, modelId);
    IfcOpenShellClient.ImportStateResponse imported = ifcOpenShellClient.importState(sourceIfcPath);

    List<MetadataEntry> metadata =
        storageService.writeMetadata(projectId, modelId, safeList(imported.metadata()));
    List<FurnitureItem> furniture =
        storageService.writeFurniture(projectId, modelId, safeList(imported.furniture()));
    List<HistoryEntry> history =
        storageService.writeHistory(projectId, modelId, safeList(imported.history()));

    return new IfcImportStateResponse(
        modelId,
        metadata.size(),
        furniture.size(),
        history.size(),
        safeList(imported.warnings()));
  }

  @PostMapping("/models/{modelId}/ifc/export-state")
  public IfcExportStateResponse exportIfcState(
      @PathVariable String projectId,
      @PathVariable String modelId) {
    Path sourceIfcPath = storageService.getModelIfcPath(projectId, modelId);
    Path targetIfcPath = storageService.createModelExportIfcPath(projectId, modelId, "ifc-state");

    List<MetadataEntry> metadata = storageService.readMetadata(projectId, modelId);
    List<FurnitureItem> furniture = storageService.readFurniture(projectId, modelId);
    List<HistoryEntry> history = storageService.readHistory(projectId, modelId);

    IfcOpenShellClient.ExportStateResponse exported =
        ifcOpenShellClient.exportState(sourceIfcPath, targetIfcPath, metadata, furniture, history);

    String exportFileName = targetIfcPath.getFileName().toString();
    if (exported.targetIfcPath() != null && !exported.targetIfcPath().isBlank()) {
      Path returnedPath = Path.of(exported.targetIfcPath());
      if (returnedPath.getFileName() != null) {
        exportFileName = returnedPath.getFileName().toString();
      }
    }

    return new IfcExportStateResponse(
        modelId,
        exportFileName,
        exported.exportedMetadataCount(),
        exported.exportedFurnitureCount(),
        exported.exportedHistoryCount(),
        safeList(exported.warnings()));
  }

  @GetMapping(path = "/models/{modelId}/ifc/exports/{fileName:.+}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<Resource> downloadIfcExport(
      @PathVariable String projectId,
      @PathVariable String modelId,
      @PathVariable String fileName) {
    Path exportPath = storageService.getModelExportIfcPath(projectId, modelId, fileName);
    FileStorageService.StoredModelInfo modelInfo = storageService.getModelInfo(projectId, modelId);
    Resource resource = new FileSystemResource(exportPath);
    String downloadName = buildExportDownloadName(modelInfo.fileName(), fileName);

    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(downloadName).build().toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(resource);
  }

  // Load full metadata list for a project.
  @GetMapping("/metadata")
  public List<MetadataEntry> getMetadata(@PathVariable String projectId) {
    return storageService.readMetadata(projectId);
  }

  // Replace metadata list for a project (simple PUT for the prototype).
  @PutMapping(path = "/metadata", consumes = MediaType.APPLICATION_JSON_VALUE)
  public List<MetadataEntry> putMetadata(
      @PathVariable String projectId,
      @RequestBody(required = false) List<MetadataEntry> items) {
    return storageService.writeMetadata(projectId, items);
  }

  @GetMapping("/models/{modelId}/metadata")
  public List<MetadataEntry> getModelMetadata(
      @PathVariable String projectId,
      @PathVariable String modelId) {
    return storageService.readMetadata(projectId, modelId);
  }

  @PutMapping(path = "/models/{modelId}/metadata", consumes = MediaType.APPLICATION_JSON_VALUE)
  public List<MetadataEntry> putModelMetadata(
      @PathVariable String projectId,
      @PathVariable String modelId,
      @RequestBody(required = false) List<MetadataEntry> items) {
    return storageService.writeMetadata(projectId, modelId, items);
  }

  // Load full furniture list for a project.
  @GetMapping("/furniture")
  public List<FurnitureItem> getFurniture(@PathVariable String projectId) {
    return storageService.readFurniture(projectId);
  }

  // Replace furniture list for a project (simple PUT for the prototype).
  @PutMapping(path = "/furniture", consumes = MediaType.APPLICATION_JSON_VALUE)
  public List<FurnitureItem> putFurniture(
      @PathVariable String projectId,
      @RequestBody(required = false) List<FurnitureItem> items) {
    return storageService.writeFurniture(projectId, items);
  }

  @GetMapping("/models/{modelId}/furniture")
  public List<FurnitureItem> getModelFurniture(
      @PathVariable String projectId,
      @PathVariable String modelId) {
    return storageService.readFurniture(projectId, modelId);
  }

  @PutMapping(path = "/models/{modelId}/furniture", consumes = MediaType.APPLICATION_JSON_VALUE)
  public List<FurnitureItem> putModelFurniture(
      @PathVariable String projectId,
      @PathVariable String modelId,
      @RequestBody(required = false) List<FurnitureItem> items) {
    return storageService.writeFurniture(projectId, modelId, items);
  }

  // Load full history list for a project.
  @GetMapping("/history")
  public List<HistoryEntry> getHistory(@PathVariable String projectId) {
    return storageService.readHistory(projectId);
  }

  // Replace history list for a project (simple PUT for the prototype).
  @PutMapping(path = "/history", consumes = MediaType.APPLICATION_JSON_VALUE)
  public List<HistoryEntry> putHistory(
      @PathVariable String projectId,
      @RequestBody(required = false) List<HistoryEntry> items) {
    return storageService.writeHistory(projectId, items);
  }

  @GetMapping("/models/{modelId}/history")
  public List<HistoryEntry> getModelHistory(
      @PathVariable String projectId,
      @PathVariable String modelId) {
    return storageService.readHistory(projectId, modelId);
  }

  @PutMapping(path = "/models/{modelId}/history", consumes = MediaType.APPLICATION_JSON_VALUE)
  public List<HistoryEntry> putModelHistory(
      @PathVariable String projectId,
      @PathVariable String modelId,
      @RequestBody(required = false) List<HistoryEntry> items) {
    return storageService.writeHistory(projectId, modelId, items);
  }

  private <T> List<T> safeList(List<T> items) {
    return items != null ? items : new ArrayList<>();
  }

  private String buildExportDownloadName(String originalFileName, String fallbackName) {
    if (originalFileName == null || originalFileName.isBlank()) {
      return fallbackName;
    }
    String base = originalFileName;
    int dotIndex = base.lastIndexOf('.');
    if (dotIndex > 0) {
      base = base.substring(0, dotIndex);
    }
    if (base.isBlank()) {
      return fallbackName;
    }
    return base + "-state.ifc";
  }

  public record IfcImportStateResponse(
      String modelId,
      int importedMetadata,
      int importedFurniture,
      int importedHistory,
      List<String> warnings) {}

  public record IfcExportStateResponse(
      String modelId,
      String exportFileName,
      int exportedMetadata,
      int exportedFurniture,
      int exportedHistory,
      List<String> warnings) {}
}

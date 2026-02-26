package cz.ifc.backend.controller;

import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import cz.ifc.backend.storage.FileStorageService;
import java.util.List;
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
  private final FileStorageService storageService;

  public ProjectDataController(FileStorageService storageService) {
    this.storageService = storageService;
  }

  @GetMapping("/models")
  public List<FileStorageService.StoredModelInfo> listModels(@PathVariable String projectId) {
    return storageService.listModels(projectId);
  }

  @PostMapping(path = "/models", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public FileStorageService.StoredModelInfo uploadModel(
      @PathVariable String projectId,
      @RequestParam("file") MultipartFile file) {
    return storageService.storeUploadedModel(projectId, file);
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
}

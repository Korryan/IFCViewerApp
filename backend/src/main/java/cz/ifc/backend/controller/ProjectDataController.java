package cz.ifc.backend.controller;

import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import cz.ifc.backend.storage.FileStorageService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(path = "/projects/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectDataController {
  private final FileStorageService storageService;

  public ProjectDataController(FileStorageService storageService) {
    this.storageService = storageService;
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
}

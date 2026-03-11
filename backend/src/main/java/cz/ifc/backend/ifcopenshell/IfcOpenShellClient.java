package cz.ifc.backend.ifcopenshell;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IfcOpenShellClient {
  private static final Logger log = LoggerFactory.getLogger(IfcOpenShellClient.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String baseUrl;

  public IfcOpenShellClient(
      ObjectMapper objectMapper,
      @Value("${ifcopenshell.base-url:http://localhost:8090}") String baseUrl) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    this.baseUrl = normalizeBaseUrl(baseUrl);
  }

  public ImportStateResponse importState(Path sourceIfcPath) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("source_ifc_path", sourceIfcPath.toString());
    return postJson("/state/import", payload, ImportStateResponse.class);
  }

  public ExportStateResponse exportState(
      Path sourceIfcPath,
      Path targetIfcPath,
      List<MetadataEntry> metadata,
      List<FurnitureItem> furniture,
      List<HistoryEntry> history) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("source_ifc_path", sourceIfcPath.toString());
    payload.put("target_ifc_path", targetIfcPath.toString());
    payload.put("metadata", metadata != null ? metadata : List.of());
    payload.put("furniture", furniture != null ? furniture : List.of());
    payload.put("history", history != null ? history : List.of());
    return postJson("/state/export", payload, ExportStateResponse.class);
  }

  private <T> T postJson(String endpoint, Object payload, Class<T> responseType) {
    try {
      String requestBody = objectMapper.writeValueAsString(payload);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + endpoint))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      if (response.statusCode() >= 400) {
        log.warn(
            "IfcOpenShell request {} failed: status={}, body={}",
            endpoint,
            response.statusCode(),
            response.body());
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "IfcOpenShell service returned HTTP " + response.statusCode());
      }

      return objectMapper.readValue(response.body(), responseType);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "IfcOpenShell request was interrupted", ex);
    } catch (IOException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Failed to call IfcOpenShell service", ex);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Invalid IfcOpenShell endpoint configuration", ex);
    }
  }

  private String normalizeBaseUrl(String rawBaseUrl) {
    String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("ifcopenshell.base-url must not be empty");
    }
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  public record ImportStateResponse(
      List<MetadataEntry> metadata,
      List<FurnitureItem> furniture,
      List<HistoryEntry> history,
      List<String> warnings) {}

  public record ExportStateResponse(
      @JsonProperty("target_ifc_path") String targetIfcPath,
      @JsonProperty("exported_metadata_count") int exportedMetadataCount,
      @JsonProperty("exported_furniture_count") int exportedFurnitureCount,
      @JsonProperty("exported_history_count") int exportedHistoryCount,
      List<String> warnings) {}
}

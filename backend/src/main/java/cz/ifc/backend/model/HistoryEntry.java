package cz.ifc.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HistoryEntry {
  // Simple change log entry for an IFC element.
  private Long ifcId;
  private String label;
  private Instant timestamp;

  public HistoryEntry() {
  }

  public Long getIfcId() {
    return ifcId;
  }

  public void setIfcId(Long ifcId) {
    this.ifcId = ifcId;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }
}

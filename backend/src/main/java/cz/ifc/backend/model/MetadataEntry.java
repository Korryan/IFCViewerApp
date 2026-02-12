package cz.ifc.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetadataEntry {
  // One IFC element record saved in the file-backed store.
  private Long ifcId;
  private String type;
  private Map<String, Object> custom;
  // World-space position of the element (if moved in the viewer).
  private Vector3 position;
  // Marks an IFC element as removed in the viewer (soft delete).
  private Boolean deleted;
  private Instant updatedAt;

  public MetadataEntry() {
  }

  public Long getIfcId() {
    return ifcId;
  }

  public void setIfcId(Long ifcId) {
    this.ifcId = ifcId;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Map<String, Object> getCustom() {
    return custom;
  }

  public void setCustom(Map<String, Object> custom) {
    this.custom = custom;
  }

  public Vector3 getPosition() {
    return position;
  }

  public void setPosition(Vector3 position) {
    this.position = position;
  }

  public Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(Boolean deleted) {
    this.deleted = deleted;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}

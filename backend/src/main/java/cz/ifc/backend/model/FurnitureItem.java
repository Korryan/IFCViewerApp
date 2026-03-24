package cz.ifc.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FurnitureItem {
  // Custom furniture / scene object placed on top of the IFC model.
  private String id;
  private String model;
  private String name;
  private Vector3 position;
  private Vector3 rotation;
  private Vector3 scale;
  private String roomNumber;
  private Integer spaceIfcId;
  private Map<String, String> custom;
  private FurnitureGeometry geometry;
  private Instant updatedAt;

  public FurnitureItem() {
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Vector3 getPosition() {
    return position;
  }

  public void setPosition(Vector3 position) {
    this.position = position;
  }

  public Vector3 getRotation() {
    return rotation;
  }

  public void setRotation(Vector3 rotation) {
    this.rotation = rotation;
  }

  public Vector3 getScale() {
    return scale;
  }

  public void setScale(Vector3 scale) {
    this.scale = scale;
  }

  public String getRoomNumber() {
    return roomNumber;
  }

  public void setRoomNumber(String roomNumber) {
    this.roomNumber = roomNumber;
  }

  public Integer getSpaceIfcId() {
    return spaceIfcId;
  }

  public void setSpaceIfcId(Integer spaceIfcId) {
    this.spaceIfcId = spaceIfcId;
  }

  public Map<String, String> getCustom() {
    return custom;
  }

  public void setCustom(Map<String, String> custom) {
    this.custom = custom;
  }

  public FurnitureGeometry getGeometry() {
    return geometry;
  }

  public void setGeometry(FurnitureGeometry geometry) {
    this.geometry = geometry;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}

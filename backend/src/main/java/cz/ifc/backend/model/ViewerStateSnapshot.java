package cz.ifc.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ViewerStateSnapshot {
  // Captures the last persisted navigation mode used by the editor viewer.
  private String navigationMode;
  // Captures whether the room-only transform guard was enabled in the editor.
  private Boolean roomOnlyTransformGuard;
  // Captures whether the shortcuts overlay was open in the editor.
  private Boolean shortcutsOpen;
  // Captures the last persisted camera position from the editor viewer.
  private Vector3 cameraPosition;
  // Captures the last persisted camera target from the editor viewer.
  private Vector3 cameraTarget;

  public String getNavigationMode() {
    return navigationMode;
  }

  public void setNavigationMode(String navigationMode) {
    this.navigationMode = navigationMode;
  }

  public Boolean getRoomOnlyTransformGuard() {
    return roomOnlyTransformGuard;
  }

  public void setRoomOnlyTransformGuard(Boolean roomOnlyTransformGuard) {
    this.roomOnlyTransformGuard = roomOnlyTransformGuard;
  }

  public Boolean getShortcutsOpen() {
    return shortcutsOpen;
  }

  public void setShortcutsOpen(Boolean shortcutsOpen) {
    this.shortcutsOpen = shortcutsOpen;
  }

  public Vector3 getCameraPosition() {
    return cameraPosition;
  }

  public void setCameraPosition(Vector3 cameraPosition) {
    this.cameraPosition = cameraPosition;
  }

  public Vector3 getCameraTarget() {
    return cameraTarget;
  }

  public void setCameraTarget(Vector3 cameraTarget) {
    this.cameraTarget = cameraTarget;
  }
}

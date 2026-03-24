package cz.ifc.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FurnitureGeometry {
  private List<Double> positions;
  private List<Integer> indices;

  public FurnitureGeometry() {
  }

  public List<Double> getPositions() {
    return positions;
  }

  public void setPositions(List<Double> positions) {
    this.positions = positions;
  }

  public List<Integer> getIndices() {
    return indices;
  }

  public void setIndices(List<Integer> indices) {
    this.indices = indices;
  }
}

package cz.ifc.backend.storage;

import cz.ifc.backend.model.FurnitureItem;
import cz.ifc.backend.model.HistoryEntry;
import cz.ifc.backend.model.MetadataEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class StorageStateNormalizer {
  private StorageStateNormalizer() {}

  // Normalizes metadata entries and stamps them with one server-side updatedAt timestamp.
  static List<MetadataEntry> normalizeMetadata(List<MetadataEntry> items) {
    Instant now = Instant.now();
    List<MetadataEntry> normalized = new ArrayList<>();
    if (items == null) {
      return normalized;
    }
    for (MetadataEntry item : items) {
      if (item == null) {
        continue;
      }
      item.setUpdatedAt(now);
      normalized.add(item);
    }
    return normalized;
  }

  // Normalizes furniture entries and stamps them with one server-side updatedAt timestamp.
  static List<FurnitureItem> normalizeFurniture(List<FurnitureItem> items) {
    Instant now = Instant.now();
    List<FurnitureItem> normalized = new ArrayList<>();
    if (items == null) {
      return normalized;
    }
    for (FurnitureItem item : items) {
      if (item == null) {
        continue;
      }
      item.setUpdatedAt(now);
      normalized.add(item);
    }
    return normalized;
  }

  // Normalizes history entries while dropping invalid rows and backfilling missing timestamps.
  static List<HistoryEntry> normalizeHistory(List<HistoryEntry> items) {
    Instant now = Instant.now();
    List<HistoryEntry> normalized = new ArrayList<>();
    if (items == null) {
      return normalized;
    }
    for (HistoryEntry item : items) {
      if (item == null || item.getIfcId() == null || item.getLabel() == null) {
        continue;
      }
      if (item.getTimestamp() == null) {
        item.setTimestamp(now);
      }
      normalized.add(item);
    }
    return normalized;
  }
}

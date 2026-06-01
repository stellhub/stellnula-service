package io.github.stellnula.domain;

import java.util.List;

public record ConfigSnapshot(long revision, String checksum, List<ConfigEntry> entries) {

  public ConfigSnapshot {
    entries = List.copyOf(entries);
  }
}

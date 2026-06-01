package io.github.stellnula.domain;

import java.util.List;

public record ConfigDelta(
    long fromRevision,
    long toRevision,
    String checksum,
    List<ConfigChange> changes,
    boolean fullSyncRequired,
    String fullSyncReason) {

  public ConfigDelta(
      long fromRevision, long toRevision, String checksum, List<ConfigChange> changes) {
    this(fromRevision, toRevision, checksum, changes, false, "");
  }

  public ConfigDelta {
    changes = List.copyOf(changes);
    fullSyncReason = fullSyncReason == null ? "" : fullSyncReason;
  }
}

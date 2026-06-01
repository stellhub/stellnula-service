package io.github.stellnula.domain;

import java.util.List;

public record WatchResult(
    WatchStatus status,
    long latestRevision,
    String latestChecksum,
    boolean fullSyncRequired,
    String fullSyncReason,
    List<ConfigChange> changes) {

  public WatchResult(
      WatchStatus status,
      long latestRevision,
      String latestChecksum,
      boolean fullSyncRequired,
      List<ConfigChange> changes) {
    this(status, latestRevision, latestChecksum, fullSyncRequired, "", changes);
  }

  public WatchResult {
    changes = List.copyOf(changes);
    fullSyncReason = fullSyncReason == null ? "" : fullSyncReason;
  }
}

package io.github.stellnula.domain;

import java.time.OffsetDateTime;
import java.util.Map;

public record DataPlaneNodeRecord(
    String serverId,
    String httpAddress,
    String grpcAddress,
    String region,
    String zone,
    int weight,
    String status,
    boolean healthy,
    int activeWatchCount,
    double loadScore,
    int failureCount,
    Map<String, String> metadata,
    OffsetDateTime lastProbeAt,
    OffsetDateTime drainStartedAt,
    OffsetDateTime offlineAt,
    OffsetDateTime lastHeartbeatAt,
    OffsetDateTime registeredAt,
    OffsetDateTime updatedAt) {

  public DataPlaneNodeRecord {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}

package io.github.stellnula.repository;

import java.util.Map;

public record DataPlaneNodeRegistration(
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
    Map<String, String> metadata) {

  public DataPlaneNodeRegistration(
      String serverId,
      String httpAddress,
      String grpcAddress,
      String region,
      String zone,
      int weight,
      boolean healthy,
      Map<String, String> metadata) {
    this(
        serverId,
        httpAddress,
        grpcAddress,
        region,
        zone,
        weight,
        "ACTIVE",
        healthy,
        0,
        0,
        metadata);
  }

  public DataPlaneNodeRegistration {
    status = status == null || status.isBlank() ? "ACTIVE" : status;
    activeWatchCount = Math.max(0, activeWatchCount);
    loadScore = Math.max(0, loadScore);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}

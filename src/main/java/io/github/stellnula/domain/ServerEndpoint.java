package io.github.stellnula.domain;

public record ServerEndpoint(
    String serverId,
    String httpAddress,
    String grpcAddress,
    int weight,
    String region,
    String zone,
    boolean healthy,
    String status,
    int activeWatchCount,
    double loadScore,
    int failureCount) {

  public ServerEndpoint(
      String serverId,
      String httpAddress,
      String grpcAddress,
      int weight,
      String region,
      String zone,
      boolean healthy) {
    this(serverId, httpAddress, grpcAddress, weight, region, zone, healthy, "ACTIVE", 0, 0, 0);
  }
}

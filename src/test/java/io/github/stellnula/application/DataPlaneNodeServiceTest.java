package io.github.stellnula.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stellnula.cache.DataPlaneNodeCache;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.DataPlaneNodeEndpoint;
import io.github.stellnula.domain.DataPlaneNodeRecord;
import io.github.stellnula.domain.ServerEndpoint;
import io.github.stellnula.repository.DataPlaneNodeRegistration;
import io.github.stellnula.repository.DataPlaneNodeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataPlaneNodeServiceTest {

  @Test
  void shouldExcludeDrainingNodeAndRecoverAfterActivate() {
    FakeDataPlaneNodeRepository repository = new FakeDataPlaneNodeRepository();
    repository.put(node("node-a", "ACTIVE", true, 1, 0.1));
    repository.put(node("node-b", "DRAINING", false, 0, 0.0));
    DataPlaneNodeCache cache = new DataPlaneNodeCache();
    DataPlaneNodeService service =
        new DataPlaneNodeService(
            properties(),
            endpointResolver(),
            repository,
            cache,
            new DataPlaneMetrics(new SimpleMeterRegistry()));

    service.refreshNodeCache();
    assertThat(cache.endpoints()).extracting(ServerEndpoint::serverId).containsExactly("node-a");

    service.drainNode("node-a", "maintenance");
    assertThat(cache.endpoints()).isEmpty();

    service.activateNode("node-a", "maintenance done");
    assertThat(cache.endpoints()).extracting(ServerEndpoint::serverId).containsExactly("node-a");
    assertThat(repository.nodes.get("node-a").metadata())
        .containsEntry("lifecycleReason", "maintenance done");
  }

  private DataPlaneNodeRecord node(
      String serverId, String status, boolean healthy, int activeWatchCount, double loadScore) {
    OffsetDateTime now = OffsetDateTime.now();
    return new DataPlaneNodeRecord(
        serverId,
        "http://" + serverId,
        serverId + ":9090",
        "default",
        "default",
        100,
        status,
        healthy,
        activeWatchCount,
        loadScore,
        0,
        Map.of(),
        null,
        null,
        null,
        now,
        now,
        now);
  }

  private DataPlaneProperties properties() {
    return new DataPlaneProperties(
        "default",
        "default",
        100,
        60,
        30000,
        10000,
        5000,
        15000,
        1000,
        64,
        1048576,
        30000,
        60000,
        2000,
        3,
        0,
        5000,
        60000,
        60000,
        10000,
        "v1",
        "v1",
        "0.1.0",
        "0.0.1",
        500,
        2000,
        1048576,
        4096,
        262144,
        500,
        30000,
        2.0,
        0.2,
        "test-sensitive-key",
        "sensitiveConfigAccess");
  }

  private DataPlaneNodeEndpointResolver endpointResolver() {
    return () -> new DataPlaneNodeEndpoint("node-local", "http://127.0.0.1:8060", "127.0.0.1:9090");
  }

  private static class FakeDataPlaneNodeRepository implements DataPlaneNodeRepository {

    private final Map<String, DataPlaneNodeRecord> nodes = new LinkedHashMap<>();

    void put(DataPlaneNodeRecord record) {
      nodes.put(record.serverId(), record);
    }

    @Override
    public void upsertCurrentNode(DataPlaneNodeRegistration registration) {
      put(
          new DataPlaneNodeRecord(
              registration.serverId(),
              registration.httpAddress(),
              registration.grpcAddress(),
              registration.region(),
              registration.zone(),
              registration.weight(),
              registration.status(),
              registration.healthy(),
              registration.activeWatchCount(),
              registration.loadScore(),
              0,
              registration.metadata(),
              null,
              null,
              null,
              OffsetDateTime.now(),
              OffsetDateTime.now(),
              OffsetDateTime.now()));
    }

    @Override
    public List<ServerEndpoint> findHealthyNodes(long expireMillis, int failureThreshold) {
      return nodes.values().stream()
          .filter(node -> "ACTIVE".equals(node.status()))
          .filter(DataPlaneNodeRecord::healthy)
          .filter(node -> node.failureCount() < failureThreshold)
          .map(
              node ->
                  new ServerEndpoint(
                      node.serverId(),
                      node.httpAddress(),
                      node.grpcAddress(),
                      node.weight(),
                      node.region(),
                      node.zone(),
                      node.healthy(),
                      node.status(),
                      node.activeWatchCount(),
                      node.loadScore(),
                      node.failureCount()))
          .toList();
    }

    @Override
    public List<DataPlaneNodeRecord> findProbeCandidates(long expireMillis) {
      return List.copyOf(nodes.values());
    }

    @Override
    public List<DataPlaneNodeRecord> findAllNodes() {
      return List.copyOf(nodes.values());
    }

    @Override
    public void updateNodeStatus(String serverId, String status, boolean healthy, String reason) {
      DataPlaneNodeRecord current = nodes.get(serverId);
      put(
          new DataPlaneNodeRecord(
              current.serverId(),
              current.httpAddress(),
              current.grpcAddress(),
              current.region(),
              current.zone(),
              current.weight(),
              status,
              healthy,
              current.activeWatchCount(),
              current.loadScore(),
              "ACTIVE".equals(status) ? 0 : current.failureCount(),
              Map.of("lifecycleReason", reason),
              current.lastProbeAt(),
              "DRAINING".equals(status) ? OffsetDateTime.now() : current.drainStartedAt(),
              "OFFLINE".equals(status) ? OffsetDateTime.now() : current.offlineAt(),
              current.lastHeartbeatAt(),
              current.registeredAt(),
              OffsetDateTime.now()));
    }

    @Override
    public void recordProbeResult(String serverId, boolean success) {
      DataPlaneNodeRecord current = nodes.get(serverId);
      put(
          new DataPlaneNodeRecord(
              current.serverId(),
              current.httpAddress(),
              current.grpcAddress(),
              current.region(),
              current.zone(),
              current.weight(),
              current.status(),
              success && "ACTIVE".equals(current.status()),
              current.activeWatchCount(),
              current.loadScore(),
              success ? 0 : current.failureCount() + 1,
              current.metadata(),
              OffsetDateTime.now(),
              current.drainStartedAt(),
              current.offlineAt(),
              current.lastHeartbeatAt(),
              current.registeredAt(),
              OffsetDateTime.now()));
    }

    @Override
    public int markProbeFailedNodesOffline(int failureThreshold) {
      return 0;
    }

    @Override
    public int markExpiredNodesOffline(long expireMillis) {
      return 0;
    }
  }
}

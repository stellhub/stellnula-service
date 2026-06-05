package io.github.stellnula.application;

import io.github.stellnula.cache.DataPlaneNodeCache;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.DataPlaneNodeEndpoint;
import io.github.stellnula.domain.DataPlaneNodeRecord;
import io.github.stellnula.domain.ServerEndpoint;
import io.github.stellnula.repository.DataPlaneNodeRegistration;
import io.github.stellnula.repository.DataPlaneNodeRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DataPlaneNodeService {

  private final DataPlaneProperties properties;
  private final DataPlaneNodeEndpointResolver endpointResolver;
  private final DataPlaneNodeRepository repository;
  private final DataPlaneNodeCache cache;
  private final DataPlaneMetrics metrics;
  private final AtomicReference<NodeLifecycle> localLifecycle =
      new AtomicReference<>(new NodeLifecycle("ACTIVE", true));

  /** 注册或刷新当前数据面节点心跳，并上报节点负载。 */
  public void registerCurrentNode() {
    NodeLifecycle lifecycle = localLifecycle.get();
    int activeWatchCount = metrics.activeWatchCount();
    double loadScore = loadScore(activeWatchCount);
    DataPlaneNodeEndpoint endpoint = endpointResolver.current();
    metrics.recordNodeLoad(activeWatchCount, loadScore);
    repository.upsertCurrentNode(
        new DataPlaneNodeRegistration(
            endpoint.serverId(),
            endpoint.httpAddress(),
            endpoint.grpcAddress(),
            properties.region(),
            properties.zone(),
            properties.weight(),
            lifecycle.status(),
            lifecycle.healthy(),
            activeWatchCount,
            loadScore,
            Map.of("source", "stellnula-data-plane")));
  }

  /** 刷新可返回给客户端的路由节点缓存。 */
  public void refreshNodeCache() {
    repository.markExpiredNodesOffline(properties.nodeExpireMillis());
    repository.markProbeFailedNodesOffline(properties.nodeProbeFailureThreshold());
    List<ServerEndpoint> endpoints =
        repository.findHealthyNodes(
            properties.nodeExpireMillis(), properties.nodeProbeFailureThreshold());
    cache.refresh(preferLocalZone(endpoints));
  }

  /** 查询所有数据面节点。 */
  public List<DataPlaneNodeRecord> listNodes() {
    return repository.findAllNodes();
  }

  /** 将节点切换为 DRAINING，停止进入客户端新地址列表。 */
  public void drainNode(String serverId, String reason) {
    if (isCurrentNode(serverId)) {
      localLifecycle.set(new NodeLifecycle("DRAINING", false));
    }
    repository.updateNodeStatus(serverId, "DRAINING", false, reason);
    refreshNodeCache();
  }

  /** 将节点恢复为 ACTIVE，允许重新进入客户端新地址列表。 */
  public void activateNode(String serverId, String reason) {
    if (isCurrentNode(serverId)) {
      localLifecycle.set(new NodeLifecycle("ACTIVE", true));
    }
    repository.updateNodeStatus(serverId, "ACTIVE", true, reason);
    refreshNodeCache();
  }

  /** 将节点切换为 OFFLINE。 */
  public void offlineNode(String serverId, String reason) {
    if (isCurrentNode(serverId)) {
      localLifecycle.set(new NodeLifecycle("OFFLINE", false));
    }
    repository.updateNodeStatus(serverId, "OFFLINE", false, reason);
    refreshNodeCache();
  }

  /** 判断当前节点是否正在排空或已下线。 */
  public boolean currentNodeAcceptsNewClients() {
    return "ACTIVE".equals(localLifecycle.get().status());
  }

  private double loadScore(int activeWatchCount) {
    double watchRatio = (double) activeWatchCount / Math.max(1, properties.maxConcurrentWatch());
    double weightFactor = 100.0 / Math.max(1, properties.weight());
    return Math.max(0, watchRatio * weightFactor);
  }

  /** 获取当前数据面节点 ID。 */
  public String currentServerId() {
    return endpointResolver.current().serverId();
  }

  private boolean isCurrentNode(String serverId) {
    return currentServerId().equals(serverId);
  }

  private List<ServerEndpoint> preferLocalZone(List<ServerEndpoint> endpoints) {
    return endpoints.stream()
        .sorted(
            Comparator.comparingInt(this::zoneScore)
                .thenComparingDouble(ServerEndpoint::loadScore)
                .thenComparingInt(ServerEndpoint::activeWatchCount)
                .thenComparing(Comparator.comparingInt(ServerEndpoint::weight).reversed())
                .thenComparing(ServerEndpoint::serverId))
        .toList();
  }

  private int zoneScore(ServerEndpoint endpoint) {
    if (properties.region().equals(endpoint.region())
        && properties.zone().equals(endpoint.zone())) {
      return 0;
    }
    if (properties.region().equals(endpoint.region())) {
      return 1;
    }
    return 2;
  }

  private record NodeLifecycle(String status, boolean healthy) {}
}

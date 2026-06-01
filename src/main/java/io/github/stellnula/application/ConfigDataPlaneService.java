package io.github.stellnula.application;

import io.github.stellnula.cache.DataPlaneNodeCache;
import io.github.stellnula.cache.InMemoryConfigCache;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ConfigDelta;
import io.github.stellnula.domain.ConfigSnapshot;
import io.github.stellnula.domain.DataPlaneErrorCode;
import io.github.stellnula.domain.DataPlaneException;
import io.github.stellnula.domain.ServerEndpoint;
import io.github.stellnula.domain.WatchResult;
import io.github.stellnula.domain.WatchStatus;
import io.github.stellnula.repository.ClientDataPlaneRepository;
import io.github.stellnula.repository.ClientInstanceState;
import io.github.stellnula.repository.ClientSnapshotState;
import io.github.stellnula.repository.ClientSubscriptionState;
import io.github.stellnula.repository.ConfigReleaseRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigDataPlaneService {

  private final InMemoryConfigCache cache;
  private final DataPlaneNodeCache nodeCache;
  private final ConfigReleaseRepository repository;
  private final ClientDataPlaneRepository clientRepository;
  private final DataPlaneProperties properties;
  private final DataPlaneMetrics metrics;
  private final ProtocolCompatibilityService protocolCompatibilityService;
  private final AtomicInteger activeWatchRequests = new AtomicInteger();

  /** 构建客户端首次启动响应。 */
  public BootstrapResult bootstrap(ClientContext context) {
    return bootstrap(new ClientBootstrapState(context, null, List.of()));
  }

  /** 构建客户端首次启动响应，并记录客户端实例和订阅状态。 */
  public BootstrapResult bootstrap(ClientBootstrapState state) {
    ClientContext context = validateContext(state.context().normalize());
    ConfigSnapshot snapshot = cache.snapshot(context);
    return new BootstrapResult(
        OffsetDateTime.now(),
        snapshot,
        properties.watchTimeoutMillis(),
        properties.heartbeatMillis(),
        preferredTransport(state),
        healthyEndpoints(),
        new LoadBalancingPolicy(
            "WEIGHTED_RENDEZVOUS_HASH",
            "appId:clientId:env:namespace",
            "NEXT_HEALTHY_CANDIDATE",
            properties.addressTtlSeconds()));
  }

  /** 获取客户端全量配置快照。 */
  public ConfigSnapshot fetchFull(ClientContext context) {
    return fetchFull(context, "GRPC");
  }

  /** 获取客户端全量配置快照，并记录订阅状态。 */
  public ConfigSnapshot fetchFull(ClientContext context, String transport) {
    ClientContext normalized = validateContext(context.normalize());
    return cache.snapshot(normalized);
  }

  /** 获取客户端配置增量。 */
  public ConfigDelta fetchDelta(ClientContext context, long fromRevision) {
    return fetchDelta(context, fromRevision, "GRPC");
  }

  /** 获取客户端配置增量，并记录订阅状态。 */
  public ConfigDelta fetchDelta(ClientContext context, long fromRevision, String transport) {
    ClientContext normalized = validateContext(context.normalize());
    return cache.delta(normalized, fromRevision);
  }

  /** 等待并返回配置变更。 */
  public WatchResult watch(ClientContext context, long currentRevision, int timeoutMillis)
      throws InterruptedException {
    ClientContext normalized = validateContext(context.normalize());
    int active = activeWatchRequests.incrementAndGet();
    metrics.recordWatchWaitStarted(active);
    if (active > properties.maxConcurrentWatch()) {
      metrics.recordWatchWaitFinished(activeWatchRequests.decrementAndGet());
      throw new DataPlaneException(
          DataPlaneErrorCode.TOO_MANY_WATCHES,
          "too many concurrent watch requests",
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          true,
          properties.clientRetryInitialMillis(),
          protocolCompatibilityService.retryBackoff(),
          false,
          "");
    }
    try {
      int effectiveTimeout = effectiveWatchTimeout(normalized, timeoutMillis);
      if (cache.isTooOld(currentRevision)) {
        ConfigSnapshot snapshot = cache.snapshot(normalized);
        return new WatchResult(
            WatchStatus.CLIENT_TOO_OLD,
            snapshot.revision(),
            snapshot.checksum(),
            true,
            "EVENT_WINDOW_EXPIRED",
            List.of());
      }
      long latestRevision = cache.waitForRevisionChange(currentRevision, effectiveTimeout);
      if (latestRevision <= currentRevision) {
        ConfigSnapshot snapshot = cache.snapshot(normalized);
        return new WatchResult(
            WatchStatus.NO_CHANGE, snapshot.revision(), snapshot.checksum(), false, List.of());
      }
      ConfigDelta delta = cache.delta(normalized, currentRevision);
      if (delta.fullSyncRequired()) {
        return new WatchResult(
            WatchStatus.CLIENT_TOO_OLD,
            delta.toRevision(),
            delta.checksum(),
            true,
            delta.fullSyncReason(),
            List.of());
      }
      if (delta.changes().isEmpty()) {
        return new WatchResult(
            WatchStatus.NO_CHANGE, delta.toRevision(), delta.checksum(), false, List.of());
      }
      return new WatchResult(
          WatchStatus.CHANGED, delta.toRevision(), delta.checksum(), false, delta.changes());
    } finally {
      metrics.recordWatchWaitFinished(activeWatchRequests.decrementAndGet());
    }
  }

  /** 记录客户端同步状态。 */
  public ClientStateResult reportClientState(ClientSnapshotState state) {
    return reportClientState(state, null, null, null);
  }

  /** 记录客户端同步状态、实例上下文和订阅 revision。 */
  public ClientStateResult reportClientState(
      ClientSnapshotState state, ClientContext context, String sdkVersion, String hostName) {
    try {
      repository.upsertClientSnapshot(state);
      ClientContext effectiveContext =
          context == null
              ? new ClientContext(
                      state.appId(),
                      state.clientId(),
                      state.env(),
                      state.region(),
                      state.zone(),
                      state.cluster(),
                      state.namespaceCode(),
                      state.groupCode())
                  .normalize()
              : context.normalize();
      observeClientInstance(effectiveContext, sdkVersion, hostName);
      metrics.observeClient(effectiveContext);
      observeSubscription(
          effectiveContext,
          state.localRevision(),
          state.localChecksum() == null ? "" : state.localChecksum(),
          "GRPC");
      return new ClientStateResult(true, cache.latestRevision());
    } catch (RuntimeException ex) {
      log.warn("Failed to persist client snapshot state clientId={}", state.clientId(), ex);
      return new ClientStateResult(false, cache.latestRevision());
    }
  }

  /** 记录客户端同步状态、实例上下文和精细订阅。 */
  public ClientStateResult reportClientState(
      ClientSnapshotState state,
      ClientContext context,
      String sdkVersion,
      String hostName,
      List<ClientSubscriptionState> subscriptions) {
    ClientStateResult result = reportClientState(state, context, sdkVersion, hostName);
    for (ClientSubscriptionState subscription : subscriptions) {
      try {
        clientRepository.upsertClientSubscription(subscription);
      } catch (RuntimeException ex) {
        log.debug("Failed to persist fine-grained subscription clientId={}", state.clientId(), ex);
      }
    }
    return result;
  }

  private List<ServerEndpoint> healthyEndpoints() {
    return nodeCache.endpoints();
  }

  private ClientContext validateContext(ClientContext context) {
    if (context.labels().size() > properties.maxRequestLabels()) {
      throw new IllegalArgumentException("too many client labels");
    }
    return context;
  }

  private int effectiveWatchTimeout(ClientContext context, int requestedTimeoutMillis) {
    int baseTimeout =
        requestedTimeoutMillis > 0
            ? Math.min(requestedTimeoutMillis, properties.watchTimeoutMillis())
            : properties.watchTimeoutMillis();
    int jitter = Math.floorMod((context.appId() + ":" + context.clientId()).hashCode(), 1000);
    return Math.max(1000, baseTimeout - jitter);
  }

  private void observeClientInstance(ClientContext context, String sdkVersion, String hostName) {
    try {
      clientRepository.upsertClientInstance(
          new ClientInstanceState(
              context.appId(),
              context.clientId(),
              context.env(),
              context.region(),
              context.zone(),
              context.cluster(),
              context.namespaceCode(),
              context.groupCode(),
              context.clientIp(),
              hostName,
              sdkVersion,
              context.labels(),
              Map.of(),
              "ONLINE"));
    } catch (RuntimeException ex) {
      log.debug("Failed to persist client instance clientId={}", context.clientId(), ex);
    }
  }

  private void observeSubscription(
      ClientContext context, long revision, String checksum, String transport) {
    try {
      clientRepository.upsertClientSubscription(
          new ClientSubscriptionState(
              context.appId(),
              context.clientId(),
              context.env(),
              context.region(),
              context.zone(),
              context.cluster(),
              context.namespaceCode(),
              context.groupCode(),
              "ALL",
              "*",
              revision,
              checksum == null ? "" : checksum,
              normalizeTransport(transport),
              "ACTIVE"));
    } catch (RuntimeException ex) {
      log.debug("Failed to persist client subscription clientId={}", context.clientId(), ex);
    }
  }

  private String preferredTransport(ClientBootstrapState state) {
    return state.supportedTransports().stream()
            .anyMatch(transport -> "grpc".equalsIgnoreCase(transport))
        ? "GRPC"
        : "HTTP";
  }

  private String normalizeTransport(String transport) {
    return "HTTP".equalsIgnoreCase(transport) ? "HTTP" : "GRPC";
  }

  public record ClientBootstrapState(
      ClientContext context, String sdkVersion, List<String> supportedTransports) {

    public ClientBootstrapState {
      supportedTransports =
          supportedTransports == null ? List.of() : List.copyOf(supportedTransports);
    }
  }

  public record BootstrapResult(
      OffsetDateTime serverTime,
      ConfigSnapshot snapshot,
      int watchTimeoutMillis,
      long heartbeatMillis,
      String preferredTransport,
      List<ServerEndpoint> servers,
      LoadBalancingPolicy loadBalancing) {}

  public record LoadBalancingPolicy(
      String strategy, String hashKey, String failover, long ttlSeconds) {}

  public record ClientStateResult(boolean accepted, long serverRevision) {}
}

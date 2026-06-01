package io.github.stellnula.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stellnula.cache.DataPlaneNodeCache;
import io.github.stellnula.cache.InMemoryConfigCache;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ClientSubscriptionFilter;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayRule;
import io.github.stellnula.domain.ConfigScope;
import io.github.stellnula.domain.ServerEndpoint;
import io.github.stellnula.domain.WatchStatus;
import io.github.stellnula.repository.ClientDataPlaneRepository;
import io.github.stellnula.repository.ClientInstanceState;
import io.github.stellnula.repository.ClientSnapshotState;
import io.github.stellnula.repository.ClientSubscriptionState;
import io.github.stellnula.repository.ConfigReleaseRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigDataPlaneServiceTest {

  @Test
  void shouldBootstrapFromMemoryOnlyAndPersistClientStateOnHeartbeat() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    cache.rebuild(
        List.of(
            new ConfigEntry(
                "timeout",
                "timeout",
                "APPLICATION",
                "order-service",
                "default",
                "default",
                "KV",
                "1000",
                5,
                5,
                false,
                new ConfigScope(1, "prod", "sg", "sg-a", "default", "INHERITABLE"))));
    DataPlaneNodeCache nodeCache = new DataPlaneNodeCache();
    nodeCache.refresh(
        List.of(
            new ServerEndpoint(
                "node-cache", "http://10.0.0.1:8060", "10.0.0.1:9090", 100, "sg", "sg-a", true)));
    FakeClientDataPlaneRepository clientRepository = new FakeClientDataPlaneRepository();
    ConfigDataPlaneService service =
        new ConfigDataPlaneService(
            cache,
            nodeCache,
            new EmptyConfigReleaseRepository(),
            clientRepository,
            properties(),
            new DataPlaneMetrics(new SimpleMeterRegistry()),
            new ProtocolCompatibilityService(properties()));

    ConfigDataPlaneService.BootstrapResult result =
        service.bootstrap(
            new ConfigDataPlaneService.ClientBootstrapState(
                new ClientContext(
                    "order-service",
                    "client-1",
                    "prod",
                    "sg",
                    "sg-a",
                    "default",
                    "default",
                    "10.0.0.8",
                    Map.of("env", "gray")),
                "java-sdk-test",
                List.of("grpc")));

    assertThat(result.servers()).extracting(ServerEndpoint::serverId).containsExactly("node-cache");
    assertThat(clientRepository.instances).isEmpty();
    assertThat(clientRepository.subscriptions).isEmpty();

    service.reportClientState(
        new ClientSnapshotState(
            "order-service",
            "client-1",
            "prod",
            "sg",
            "sg-a",
            "default",
            "default",
            "default",
            5,
            "checksum",
            true,
            null),
        new ClientContext(
            "order-service",
            "client-1",
            "prod",
            "sg",
            "sg-a",
            "default",
            "default",
            "10.0.0.8",
            Map.of("env", "gray")),
        "java-sdk-test",
        "host-a");

    assertThat(clientRepository.instances)
        .singleElement()
        .satisfies(
            state -> {
              assertThat(state.clientIp()).isEqualTo("10.0.0.8");
              assertThat(state.sdkVersion()).isEqualTo("java-sdk-test");
              assertThat(state.hostName()).isEqualTo("host-a");
              assertThat(state.labels()).containsEntry("env", "gray");
            });
    assertThat(clientRepository.subscriptions)
        .singleElement()
        .satisfies(
            state -> {
              assertThat(state.subscriptionType()).isEqualTo("ALL");
              assertThat(state.subscriptionKey()).isEqualTo("*");
              assertThat(state.currentRevision()).isEqualTo(5);
              assertThat(state.transport()).isEqualTo("GRPC");
            });
  }

  @Test
  void shouldReturnNoChangeWhenWatchRevisionOnlyContainsUnsubscribedEvents()
      throws InterruptedException {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry timeoutConfig =
        new ConfigEntry(
            "app-timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "default",
            "default",
            "KV",
            "1000",
            2,
            2,
            false,
            new ConfigScope(1, "prod", "sg", "sg-a", "default", "INHERITABLE"));
    ConfigEntry threadConfig =
        new ConfigEntry(
            "app-thread",
            "thread.pool",
            "APPLICATION",
            "order-service",
            "default",
            "default",
            "KV",
            "16",
            2,
            3,
            false,
            new ConfigScope(1, "prod", "sg", "sg-a", "default", "INHERITABLE"));
    cache.rebuild(
        List.of(timeoutConfig, threadConfig),
        List.of(),
        List.of(timeoutConfig, threadConfig),
        List.of(),
        100);
    ConfigDataPlaneService service =
        new ConfigDataPlaneService(
            cache,
            new DataPlaneNodeCache(),
            new EmptyConfigReleaseRepository(),
            new FakeClientDataPlaneRepository(),
            properties(),
            new DataPlaneMetrics(new SimpleMeterRegistry()),
            new ProtocolCompatibilityService(properties()));

    var result =
        service.watch(
            new ClientContext(
                "order-service",
                "client-1",
                "prod",
                "sg",
                "sg-a",
                "default",
                "default",
                "",
                Map.of(),
                List.of(new ClientSubscriptionFilter("default", "CONFIG", "app-timeout"))),
            2,
            1000);

    assertThat(result.status()).isEqualTo(WatchStatus.NO_CHANGE);
    assertThat(result.latestRevision()).isEqualTo(3);
    assertThat(result.changes()).isEmpty();
  }

  private DataPlaneProperties properties() {
    return new DataPlaneProperties(
        "node-local",
        "http://127.0.0.1:8060",
        "127.0.0.1:9090",
        "default",
        "default",
        100,
        60,
        30000,
        10000,
        5000,
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

  private static class FakeClientDataPlaneRepository implements ClientDataPlaneRepository {

    private final List<ClientInstanceState> instances = new ArrayList<>();
    private final List<ClientSubscriptionState> subscriptions = new ArrayList<>();

    @Override
    public void upsertClientInstance(ClientInstanceState state) {
      instances.add(state);
    }

    @Override
    public void upsertClientSubscription(ClientSubscriptionState state) {
      subscriptions.add(state);
    }
  }

  private static class EmptyConfigReleaseRepository implements ConfigReleaseRepository {

    @Override
    public List<ConfigEntry> loadLatestPublishedEntries() {
      return List.of();
    }

    @Override
    public List<ConfigGrayRule> loadClientVisibleGrayRules() {
      return List.of();
    }

    @Override
    public List<ConfigEntry> loadRecentReleaseEvents(int limit) {
      return List.of();
    }

    @Override
    public List<ConfigEntry> loadReleaseEventsAfter(long revision, int limit) {
      return List.of();
    }

    @Override
    public List<Long> loadChangeEventRevisionsAfter(long revision, int limit) {
      return List.of();
    }

    @Override
    public List<ConfigGrayRule> loadRecentGrayRuleEvents(int limit) {
      return List.of();
    }

    @Override
    public List<ConfigGrayRule> loadGrayRuleEventsAfter(long revision, int limit) {
      return List.of();
    }

    @Override
    public long findMaxRevision() {
      return 0;
    }

    @Override
    public void upsertClientSnapshot(ClientSnapshotState state) {}
  }
}

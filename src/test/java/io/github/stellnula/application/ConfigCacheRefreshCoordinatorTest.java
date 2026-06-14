package io.github.stellnula.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.cache.InMemoryConfigCache;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayImpactClient;
import io.github.stellnula.domain.ConfigGrayMutationCommand;
import io.github.stellnula.domain.ConfigGrayMutationResult;
import io.github.stellnula.domain.ConfigGrayRecord;
import io.github.stellnula.domain.ConfigGrayRule;
import io.github.stellnula.domain.ConfigGrayRuleExpiry;
import io.github.stellnula.domain.ConfigScope;
import io.github.stellnula.repository.ClientSnapshotState;
import io.github.stellnula.repository.ConfigGrayRuleRepository;
import io.github.stellnula.repository.ConfigReleaseRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigCacheRefreshCoordinatorTest {

  @Test
  void shouldRefreshLocalCacheImmediatelyAfterVisibleRevisionPublished() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry oldEntry = entry("1000", 1);
    ConfigEntry newEntry = entry("2000", 2);
    cache.rebuild(List.of(oldEntry), List.of(), List.of(oldEntry), List.of(), 100);
    FakeConfigReleaseRepository repository = new FakeConfigReleaseRepository();
    repository.changeEventRevisions = List.of(2L);
    repository.releaseEvents = List.of(newEntry);
    ConfigCacheLoader loader =
        new ConfigCacheLoader(
            repository, cache, new DataPlaneMetrics(new SimpleMeterRegistry()), properties());
    ConfigCacheRefreshCoordinator coordinator = new ConfigCacheRefreshCoordinator(cache, loader);

    coordinator.refreshVisibleRevision(2, "test");

    assertThat(cache.latestRevision()).isEqualTo(2);
    assertThat(cache.snapshot(context()).entries())
        .singleElement()
        .extracting(ConfigEntry::value)
        .isEqualTo("2000");
    assertThat(repository.loadLatestPublishedEntriesCalls).isZero();
  }

  @Test
  void shouldKeepScheduledIncrementalRefreshFromFallingBackToFullReload() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    cache.rebuild(List.of(entry("1000", 1)), List.of(), List.of(entry("1000", 1)), List.of(), 100);
    FakeConfigReleaseRepository repository = new FakeConfigReleaseRepository();
    repository.maxRevision = 2;
    ConfigCacheLoader loader =
        new ConfigCacheLoader(
            repository, cache, new DataPlaneMetrics(new SimpleMeterRegistry()), properties());
    ConfigCacheRefreshScheduler scheduler =
        new ConfigCacheRefreshScheduler(
            repository,
            () -> 2,
            cache,
            loader,
            new ConfigGrayRuleService(
                new EmptyConfigGrayRuleRepository(),
                new ObjectMapper(),
                new GrayRuleMatcher(new ObjectMapper())),
            new DataPlaneMetrics(new SimpleMeterRegistry()),
            properties());

    scheduler.refreshIfNeeded();

    assertThat(cache.latestRevision()).isEqualTo(1);
    assertThat(repository.loadLatestPublishedEntriesCalls).isZero();
  }

  private ConfigEntry entry(String value, long revision) {
    return new ConfigEntry(
        "app-timeout",
        "timeout",
        "APPLICATION",
        "order-service",
        "default",
        "default",
        "KV",
        value,
        revision,
        revision,
        false,
        new ConfigScope(1, "prod", "default", "default", "default", "INHERITABLE"));
  }

  private ClientContext context() {
    return new ClientContext(
        "order-service", "client-1", "prod", "default", "default", "default", "default", "default");
  }

  private DataPlaneProperties properties() {
    return new DataPlaneProperties(
        "default",
        "default",
        100,
        60,
        30000,
        10000,
        1000,
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

  private static class FakeConfigReleaseRepository implements ConfigReleaseRepository {

    private List<Long> changeEventRevisions = List.of();
    private List<ConfigEntry> releaseEvents = List.of();
    private long maxRevision;
    private int loadLatestPublishedEntriesCalls;

    @Override
    public List<ConfigEntry> loadLatestPublishedEntries() {
      loadLatestPublishedEntriesCalls++;
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
      return releaseEvents;
    }

    @Override
    public List<Long> loadChangeEventRevisionsAfter(long revision, int limit) {
      return changeEventRevisions;
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
      return maxRevision;
    }

    @Override
    public void upsertClientSnapshot(ClientSnapshotState state) {}
  }

  private static class EmptyConfigGrayRuleRepository implements ConfigGrayRuleRepository {

    @Override
    public ConfigGrayMutationResult mutate(ConfigGrayMutationCommand command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ConfigGrayRecord> findLatest(
        String configId, String grayName, String env, String region, String zone, String cluster) {
      return Optional.empty();
    }

    @Override
    public List<ConfigGrayRuleExpiry> findExpiredActiveRules(OffsetDateTime now, int limit) {
      return List.of();
    }

    @Override
    public List<ConfigGrayImpactClient> findImpactCandidates(
        String configId,
        String grayName,
        String env,
        String region,
        String zone,
        String cluster,
        int limit) {
      return List.of();
    }
  }
}

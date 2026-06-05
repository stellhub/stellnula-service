package io.github.stellnula.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.cache.InMemoryConfigCache;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayImpactClient;
import io.github.stellnula.domain.ConfigGrayMutationCommand;
import io.github.stellnula.domain.ConfigGrayMutationResult;
import io.github.stellnula.domain.ConfigGrayRecord;
import io.github.stellnula.domain.ConfigGrayRule;
import io.github.stellnula.domain.ConfigGrayRuleExpiry;
import io.github.stellnula.repository.ConfigGrayRuleRepository;
import io.github.stellnula.repository.ConfigReleaseRepository;
import io.github.stellnula.repository.ConfigRevisionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class DataPlaneHealthIndicatorTest {

  @Test
  void shouldKeepReadPathUpWhenRuntimeDbRefreshFails() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    DataPlaneProperties properties = properties();
    DataPlaneMetrics metrics = new DataPlaneMetrics(new SimpleMeterRegistry());
    ConfigCacheLoader loader =
        new ConfigCacheLoader(new HealthyConfigReleaseRepository(), cache, metrics, properties);
    loader.reload();
    ConfigCacheRefreshScheduler refreshScheduler =
        new ConfigCacheRefreshScheduler(
            new FailingConfigReleaseRepository(),
            new EmptyConfigRevisionRepository(),
            cache,
            loader,
            new ConfigGrayRuleService(
                new EmptyConfigGrayRuleRepository(),
                new ObjectMapper(),
                new GrayRuleMatcher(new ObjectMapper())),
            metrics,
            properties);

    refreshScheduler.refreshIfNeeded();

    DataPlaneHealthIndicator indicator =
        new DataPlaneHealthIndicator(cache, loader, refreshScheduler, properties);
    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("readPathAvailable", true)
        .containsEntry("dbWeakDependency", "DEGRADED")
        .containsEntry("alertLevel", "WARN")
        .containsEntry("currentRevision", 0L);
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

  private static class HealthyConfigReleaseRepository implements ConfigReleaseRepository {

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
    public void upsertClientSnapshot(io.github.stellnula.repository.ClientSnapshotState state) {}
  }

  private static class FailingConfigReleaseRepository extends HealthyConfigReleaseRepository {

    @Override
    public long findMaxRevision() {
      throw new IllegalStateException("database unavailable");
    }
  }

  private static class EmptyConfigRevisionRepository implements ConfigRevisionRepository {

    @Override
    public long findLatestRevision() {
      return 0;
    }
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

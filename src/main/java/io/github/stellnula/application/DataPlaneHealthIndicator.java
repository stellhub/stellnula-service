package io.github.stellnula.application;

import io.github.stellnula.cache.InMemoryConfigCache;
import io.github.stellnula.config.DataPlaneProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

@Component("stellnulaDataPlane")
public class DataPlaneHealthIndicator implements HealthIndicator {

  private final InMemoryConfigCache cache;
  private final ConfigCacheLoader loader;
  private final ConfigCacheRefreshScheduler refreshScheduler;
  private final DataPlaneProperties properties;

  public DataPlaneHealthIndicator(
      InMemoryConfigCache cache,
      ConfigCacheLoader loader,
      ConfigCacheRefreshScheduler refreshScheduler,
      DataPlaneProperties properties) {
    this.cache = cache;
    this.loader = loader;
    this.refreshScheduler = refreshScheduler;
    this.properties = properties;
  }

  /** 输出配置中心数据面健康状态，运行时 DB 弱依赖异常只告警不阻断读路径。 */
  @Override
  public Health health() {
    HealthSignal signal = signal();
    Health.Builder builder =
        signal.readPathAvailable() ? Health.status(Status.UP) : Health.outOfService();
    return builder
        .withDetail("readPathAvailable", signal.readPathAvailable())
        .withDetail("startupCacheLoaded", signal.startupCacheLoaded())
        .withDetail("cacheStale", signal.cacheStale())
        .withDetail("cacheAgeMillis", signal.cacheAgeMillis())
        .withDetail("cacheStaleThresholdMillis", properties.cacheStaleThresholdMillis())
        .withDetail("dbWeakDependency", signal.dbWeakDependency())
        .withDetail("dbRefreshConsecutiveFailures", refreshScheduler.consecutiveFailures())
        .withDetail("dbRefreshLastFailureAt", refreshScheduler.lastFailureAt())
        .withDetail("dbRefreshLastFailureMessage", refreshScheduler.lastFailureMessage())
        .withDetail("dbRefreshNextRetryAt", refreshScheduler.nextAllowedRefreshAt())
        .withDetail("currentRevision", cache.latestRevision())
        .withDetail("minRetainedRevision", cache.minRevision())
        .withDetail("entryCount", cache.entryCount())
        .withDetail("grayRuleCount", cache.grayRuleCount())
        .withDetail("cacheLoadedAt", cache.loadedAt())
        .withDetail("lastSuccessfulRefreshAt", loader.lastSuccessfulReloadAt())
        .withDetail("alertLevel", signal.alertLevel())
        .build();
  }

  HealthSignal signal() {
    boolean startupCacheLoaded =
        loader.startupCacheLoaded() || loader.lastSuccessfulReloadAt() != null;
    OffsetDateTime lastSuccessfulRefreshAt = loader.lastSuccessfulReloadAt();
    long cacheAgeMillis =
        lastSuccessfulRefreshAt == null
            ? Long.MAX_VALUE
            : Duration.between(lastSuccessfulRefreshAt, OffsetDateTime.now()).toMillis();
    boolean cacheStale =
        lastSuccessfulRefreshAt == null || cacheAgeMillis > properties.cacheStaleThresholdMillis();
    boolean dbWeakDependencyAbnormal = refreshScheduler.consecutiveFailures() > 0;
    boolean readPathAvailable = startupCacheLoaded && cache.latestRevision() >= 0;
    String alertLevel =
        !readPathAvailable ? "CRITICAL" : (cacheStale || dbWeakDependencyAbnormal ? "WARN" : "OK");
    String dbWeakDependency = dbWeakDependencyAbnormal ? "DEGRADED" : "OK";
    return new HealthSignal(
        readPathAvailable,
        startupCacheLoaded,
        cacheStale,
        cacheAgeMillis,
        dbWeakDependency,
        alertLevel);
  }

  record HealthSignal(
      boolean readPathAvailable,
      boolean startupCacheLoaded,
      boolean cacheStale,
      long cacheAgeMillis,
      String dbWeakDependency,
      String alertLevel) {}
}

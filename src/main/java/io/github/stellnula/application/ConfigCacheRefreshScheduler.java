package io.github.stellnula.application;

import io.github.stellnula.cache.InMemoryConfigCache;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.repository.ConfigReleaseRepository;
import io.github.stellnula.repository.ConfigRevisionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigCacheRefreshScheduler {

  private final ConfigReleaseRepository repository;
  private final ConfigRevisionRepository revisionRepository;
  private final InMemoryConfigCache cache;
  private final ConfigCacheLoader loader;
  private final ConfigGrayRuleService grayRuleService;
  private final DataPlaneMetrics metrics;
  private final DataPlaneProperties properties;
  private volatile long consecutiveFailures;
  private volatile Instant nextAllowedRefreshAt = Instant.EPOCH;
  private volatile OffsetDateTime lastFailureAt;
  private volatile String lastFailureMessage = "";

  /** 定时扫描 PostgreSQL revision，只按增量刷新缓存。 */
  @Scheduled(fixedDelayString = "${stellnula.data-plane.refresh-interval-millis}")
  public void refreshIfNeeded() {
    refreshIncrementalIfNeeded();
  }

  /** 定时全量重建缓存，作为增量窗口缺失或跨节点通知延迟时的兜底。 */
  @Scheduled(fixedDelayString = "${stellnula.data-plane.full-refresh-interval-millis}")
  public void refreshFullyAsFallback() {
    if (Instant.now().isBefore(nextAllowedRefreshAt)) {
      return;
    }
    try {
      loader.reload();
      consecutiveFailures = 0;
      nextAllowedRefreshAt = Instant.EPOCH;
      lastFailureMessage = "";
    } catch (RuntimeException ex) {
      recordRefreshFailure(ex);
    }
  }

  private void refreshIncrementalIfNeeded() {
    if (Instant.now().isBefore(nextAllowedRefreshAt)) {
      return;
    }
    try {
      grayRuleService.endExpiredRules(100);
      long remoteRevision =
          Math.max(repository.findMaxRevision(), revisionRepository.findLatestRevision());
      long currentRevision = cache.latestRevision();
      if (remoteRevision > currentRevision) {
        loader.reloadIncrementalOnly(currentRevision, remoteRevision);
      }
      consecutiveFailures = 0;
      nextAllowedRefreshAt = Instant.EPOCH;
      lastFailureMessage = "";
    } catch (RuntimeException ex) {
      recordRefreshFailure(ex);
    }
  }

  private void recordRefreshFailure(RuntimeException ex) {
    consecutiveFailures++;
    lastFailureAt = OffsetDateTime.now();
    lastFailureMessage = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    long backoffMillis = nextBackoffMillis(consecutiveFailures);
    nextAllowedRefreshAt = Instant.now().plusMillis(backoffMillis);
    metrics.recordCacheRefreshFailure(consecutiveFailures);
    log.warn(
        "Failed to refresh Stellnula config cache; currentRevision={} consecutiveFailures={}"
            + " nextRetryInMillis={} lastSuccessfulReloadAt={}",
        cache.latestRevision(),
        consecutiveFailures,
        backoffMillis,
        loader.lastSuccessfulReloadAt(),
        ex);
  }

  private long nextBackoffMillis(long failures) {
    long base = properties.cacheRefreshBackoffMillis();
    long max = properties.cacheRefreshMaxBackoffMillis();
    long multiplier = failures >= 10 ? 512 : 1L << Math.max(0, failures - 1);
    return Math.min(max, base * multiplier);
  }

  /** 查询连续刷新失败次数。 */
  public long consecutiveFailures() {
    return consecutiveFailures;
  }

  /** 查询下一次允许刷新时间。 */
  public Instant nextAllowedRefreshAt() {
    return nextAllowedRefreshAt;
  }

  /** 查询最近一次刷新失败时间。 */
  public OffsetDateTime lastFailureAt() {
    return lastFailureAt;
  }

  /** 查询最近一次刷新失败摘要。 */
  public String lastFailureMessage() {
    return lastFailureMessage;
  }
}

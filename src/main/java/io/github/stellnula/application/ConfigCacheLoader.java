package io.github.stellnula.application;

import io.github.stellnula.cache.InMemoryConfigCache;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayRule;
import io.github.stellnula.repository.ConfigReleaseRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigCacheLoader implements ApplicationRunner {

  private final ConfigReleaseRepository repository;
  private final InMemoryConfigCache cache;
  private final DataPlaneMetrics metrics;
  private final DataPlaneProperties properties;
  private volatile OffsetDateTime lastSuccessfulReloadAt;
  private volatile boolean startupCacheLoaded;

  /** 启动时强依赖 PostgreSQL 构建全量缓存。 */
  @Override
  public void run(ApplicationArguments args) {
    try {
      reload();
      startupCacheLoaded = true;
    } catch (RuntimeException ex) {
      log.error("Failed to build Stellnula config cache on startup; PostgreSQL is required", ex);
      throw ex;
    }
  }

  /** 从持久化层重建缓存。 */
  public synchronized ReloadResult reload() {
    long startedNanos = System.nanoTime();
    List<ConfigEntry> entries = repository.loadLatestPublishedEntries();
    List<ConfigGrayRule> grayRules = repository.loadClientVisibleGrayRules();
    List<ConfigEntry> releaseEvents =
        repository.loadRecentReleaseEvents(properties.eventWindowSize());
    List<ConfigGrayRule> grayRuleEvents =
        repository.loadRecentGrayRuleEvents(properties.eventWindowSize());
    cache.rebuild(entries, grayRules, releaseEvents, grayRuleEvents, properties.eventWindowSize());
    Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
    lastSuccessfulReloadAt = OffsetDateTime.now();
    metrics.recordCacheRefreshSuccess(
        duration, entries.size(), grayRules.size(), cache.latestRevision());
    log.info(
        "Loaded Stellnula config cache entries={} grayRules={} latestRevision={}",
        entries.size(),
        grayRules.size(),
        cache.latestRevision());
    return new ReloadResult(
        entries.size(), grayRules.size(), cache.latestRevision(), duration, "FULL");
  }

  /** 从持久化层按 revision 增量刷新缓存，窗口不足时回退到全量重建。 */
  public synchronized ReloadResult reloadIncremental(long fromRevision, long targetRevision) {
    return reloadIncremental(fromRevision, targetRevision, true);
  }

  /** 从持久化层按 revision 增量刷新缓存，不回退到全量重建。 */
  public synchronized boolean reloadIncrementalOnly(long fromRevision, long targetRevision) {
    return reloadIncremental(fromRevision, targetRevision, false) != null;
  }

  private ReloadResult reloadIncremental(
      long fromRevision, long targetRevision, boolean allowFullFallback) {
    long startedNanos = System.nanoTime();
    List<Long> changeEventRevisions =
        repository.loadChangeEventRevisionsAfter(fromRevision, properties.eventWindowSize());
    List<ConfigEntry> releaseEvents =
        repository.loadReleaseEventsAfter(fromRevision, properties.eventWindowSize());
    List<ConfigGrayRule> grayRuleEvents =
        repository.loadGrayRuleEventsAfter(fromRevision, properties.eventWindowSize());
    long maxChangeEventRevision =
        changeEventRevisions.stream().mapToLong(Long::longValue).max().orElse(0);
    if (changeEventRevisions.isEmpty() || maxChangeEventRevision < targetRevision) {
      return fallbackOrSkip(
          allowFullFallback,
          "Change event window cannot catch up targetRevision={} maxChangeEventRevision={}",
          targetRevision,
          maxChangeEventRevision);
    }
    long maxEventRevision = maxRevision(releaseEvents, grayRuleEvents);
    if (releaseEvents.isEmpty() && grayRuleEvents.isEmpty()) {
      return fallbackOrSkip(
          allowFullFallback,
          "No cache events found after revision={} targetRevision={}",
          fromRevision,
          targetRevision);
    }
    if (maxEventRevision < targetRevision) {
      return fallbackOrSkip(
          allowFullFallback,
          "Cache event window cannot catch up targetRevision={} maxEventRevision={}",
          targetRevision,
          maxEventRevision);
    }
    boolean applied =
        cache.applyIncremental(
            releaseEvents, grayRuleEvents, properties.eventWindowSize(), targetRevision);
    if (!applied) {
      return reload();
    }
    Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
    lastSuccessfulReloadAt = OffsetDateTime.now();
    metrics.recordCacheRefreshSuccess(
        duration, cache.entryCount(), cache.grayRuleCount(), cache.latestRevision());
    log.info(
        "Incrementally refreshed Stellnula config cache releaseEvents={} grayEvents={}"
            + " latestRevision={}",
        releaseEvents.size(),
        grayRuleEvents.size(),
        cache.latestRevision());
    return new ReloadResult(
        cache.entryCount(), cache.grayRuleCount(), cache.latestRevision(), duration, "INCREMENTAL");
  }

  private ReloadResult fallbackOrSkip(
      boolean allowFullFallback, String message, Object firstArgument, Object secondArgument) {
    if (allowFullFallback) {
      log.info(message + ", fallback to full reload", firstArgument, secondArgument);
      return reload();
    }
    log.info(message + ", skip full reload on incremental path", firstArgument, secondArgument);
    return null;
  }

  /** 查询最后一次成功刷新时间。 */
  public OffsetDateTime lastSuccessfulReloadAt() {
    return lastSuccessfulReloadAt;
  }

  /** 判断启动阶段缓存是否已经成功构建。 */
  public boolean startupCacheLoaded() {
    return startupCacheLoaded;
  }

  private long maxRevision(List<ConfigEntry> releaseEvents, List<ConfigGrayRule> grayRuleEvents) {
    long releaseRevision = releaseEvents.stream().mapToLong(ConfigEntry::revision).max().orElse(0);
    long grayRevision =
        grayRuleEvents.stream().mapToLong(ConfigGrayRule::effectiveRevision).max().orElse(0);
    return Math.max(releaseRevision, grayRevision);
  }

  public record ReloadResult(
      int entryCount, int grayRuleCount, long latestRevision, Duration duration, String mode) {}
}

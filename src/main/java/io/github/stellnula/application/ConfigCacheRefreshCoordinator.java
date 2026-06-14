package io.github.stellnula.application;

import io.github.stellnula.cache.InMemoryConfigCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigCacheRefreshCoordinator {

  private final InMemoryConfigCache cache;
  private final ConfigCacheLoader loader;

  /** 在配置发布事务提交后，立即尝试把本实例缓存追到目标 revision。 */
  public synchronized void refreshVisibleRevision(long targetRevision, String source) {
    if (targetRevision <= 0) {
      return;
    }
    long currentRevision = cache.latestRevision();
    if (targetRevision <= currentRevision) {
      return;
    }
    try {
      boolean refreshed = loader.reloadIncrementalOnly(currentRevision, targetRevision);
      if (!refreshed) {
        log.info(
            "Deferred Stellnula cache refresh source={} currentRevision={} targetRevision={}",
            source,
            currentRevision,
            targetRevision);
      }
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to refresh Stellnula cache immediately source={} currentRevision={}"
              + " targetRevision={}",
          source,
          currentRevision,
          targetRevision,
          ex);
    }
  }
}

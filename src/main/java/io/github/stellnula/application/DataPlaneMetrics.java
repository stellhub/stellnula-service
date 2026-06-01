package io.github.stellnula.application;

import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ConfigGrayRule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class DataPlaneMetrics {

  private final AtomicLong cacheRevision = new AtomicLong();
  private final AtomicLong cacheEntries = new AtomicLong();
  private final AtomicLong cacheGrayRules = new AtomicLong();
  private final AtomicLong cacheLastSuccessEpochSeconds = new AtomicLong();
  private final AtomicLong cacheConsecutiveFailures = new AtomicLong();
  private final AtomicInteger activeWatchRequests = new AtomicInteger();
  private final AtomicInteger nodeActiveWatchCount = new AtomicInteger();
  private final AtomicLong nodeLoadScorePermyriad = new AtomicLong();
  private final Set<String> observedClients = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<String, Counter> grayRuleHitCounters = new ConcurrentHashMap<>();

  private final MeterRegistry registry;
  private final Timer cacheRefreshTimer;
  private final Counter dbRefreshFailures;
  private final Counter deltaHitCounter;
  private final Counter grayHitCounter;
  private final Counter watchWaitCounter;

  public DataPlaneMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.cacheRefreshTimer = Timer.builder("stellnula.cache.refresh.duration").register(registry);
    this.dbRefreshFailures =
        Counter.builder("stellnula.cache.refresh.db.failures").register(registry);
    this.deltaHitCounter = Counter.builder("stellnula.config.delta.hits").register(registry);
    this.grayHitCounter = Counter.builder("stellnula.config.gray.hits").register(registry);
    this.watchWaitCounter = Counter.builder("stellnula.config.watch.waits").register(registry);
    Gauge.builder("stellnula.cache.revision", cacheRevision, AtomicLong::get).register(registry);
    Gauge.builder("stellnula.cache.entries", cacheEntries, AtomicLong::get).register(registry);
    Gauge.builder("stellnula.cache.gray.rules", cacheGrayRules, AtomicLong::get).register(registry);
    Gauge.builder(
            "stellnula.cache.last.success.epoch.seconds",
            cacheLastSuccessEpochSeconds,
            AtomicLong::get)
        .register(registry);
    Gauge.builder(
            "stellnula.cache.refresh.consecutive.failures",
            cacheConsecutiveFailures,
            AtomicLong::get)
        .register(registry);
    Gauge.builder("stellnula.config.watch.active", activeWatchRequests, AtomicInteger::get)
        .register(registry);
    Gauge.builder("stellnula.node.watch.active", nodeActiveWatchCount, AtomicInteger::get)
        .register(registry);
    Gauge.builder(
            "stellnula.node.load.score", nodeLoadScorePermyriad, value -> value.get() / 10000.0)
        .register(registry);
    Gauge.builder("stellnula.client.instances.observed", observedClients, Set::size)
        .register(registry);
  }

  /** 记录缓存刷新成功。 */
  public void recordCacheRefreshSuccess(
      Duration duration, int entryCount, int grayRuleCount, long latestRevision) {
    cacheRefreshTimer.record(duration);
    cacheEntries.set(entryCount);
    cacheGrayRules.set(grayRuleCount);
    cacheRevision.set(latestRevision);
    cacheConsecutiveFailures.set(0);
    cacheLastSuccessEpochSeconds.set(OffsetDateTime.now().toEpochSecond());
  }

  /** 记录缓存刷新失败。 */
  public void recordCacheRefreshFailure(long consecutiveFailures) {
    dbRefreshFailures.increment();
    cacheConsecutiveFailures.set(consecutiveFailures);
  }

  /** 记录客户端 delta 命中。 */
  public void recordDeltaHit(int changeCount) {
    if (changeCount > 0) {
      deltaHitCounter.increment(changeCount);
    }
  }

  /** 记录灰度命中。 */
  public void recordGrayHit() {
    grayHitCounter.increment();
  }

  /** 记录灰度规则维度命中。 */
  public void recordGrayHit(ConfigGrayRule rule) {
    recordGrayHit();
    String key = String.join("|", rule.configId(), rule.grayName(), rule.ruleType());
    grayRuleHitCounters
        .computeIfAbsent(
            key,
            ignored ->
                Counter.builder("stellnula.config.gray.rule.hits")
                    .tag("configId", rule.configId())
                    .tag("grayName", rule.grayName())
                    .tag("ruleType", rule.ruleType())
                    .register(registry))
        .increment();
  }

  /** 记录 watch 等待。 */
  public void recordWatchWaitStarted(int activeCount) {
    watchWaitCounter.increment();
    activeWatchRequests.set(activeCount);
  }

  /** 记录 watch 结束。 */
  public void recordWatchWaitFinished(int activeCount) {
    activeWatchRequests.set(activeCount);
  }

  /** 获取当前活跃 watch 请求数。 */
  public int activeWatchCount() {
    return activeWatchRequests.get();
  }

  /** 记录当前节点负载。 */
  public void recordNodeLoad(int activeWatchCount, double loadScore) {
    nodeActiveWatchCount.set(activeWatchCount);
    nodeLoadScorePermyriad.set(Math.round(loadScore * 10000));
  }

  /** 记录客户端实例。 */
  public void observeClient(ClientContext context) {
    observedClients.add(
        String.join(
            "|",
            context.appId(),
            context.clientId(),
            context.env(),
            context.namespaceCode(),
            context.groupCode()));
  }
}

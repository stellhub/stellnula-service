package io.github.stellnula.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellflux.caffeine.StellfluxCaffeineCache;
import io.github.stellflux.caffeine.StellfluxCaffeineCacheFactory;
import io.github.stellnula.application.DataPlaneMetrics;
import io.github.stellnula.application.GrayRuleMatcher;
import io.github.stellnula.cache.ConfigCacheState.ConfigBucketKey;
import io.github.stellnula.cache.ConfigCacheState.GrayRuleKey;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ChangeType;
import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ConfigCacheEvent;
import io.github.stellnula.domain.ConfigChange;
import io.github.stellnula.domain.ConfigDelta;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayRule;
import io.github.stellnula.domain.ConfigScope;
import io.github.stellnula.domain.ConfigSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class InMemoryConfigCache {

  private static final String DEFAULT_SCOPE = "default";
  private static final String DEFAULT_SENSITIVE_ACCESS_LABEL = "sensitiveConfigAccess";
  private static final String MASKED_VALUE = "******";
  private static final long GRAY_TIME_BUCKET_SECONDS = 5;

  private final Object revisionMonitor = new Object();
  private final AtomicReference<ConfigCacheState> state =
      new AtomicReference<>(ConfigCacheState.empty());
  private final StellfluxCaffeineCache<String, ConfigSnapshot> snapshotCache;
  private final String sensitiveAccessLabel;
  private final DataPlaneMetrics metrics;
  private final GrayRuleMatcher grayRuleMatcher;

  public InMemoryConfigCache() {
    this.snapshotCache = null;
    this.sensitiveAccessLabel = DEFAULT_SENSITIVE_ACCESS_LABEL;
    this.metrics = null;
    this.grayRuleMatcher = new GrayRuleMatcher(new ObjectMapper());
  }

  @Autowired
  public InMemoryConfigCache(
      ObjectProvider<StellfluxCaffeineCacheFactory> cacheFactoryProvider,
      ObjectProvider<DataPlaneProperties> propertiesProvider,
      ObjectProvider<DataPlaneMetrics> metricsProvider,
      ObjectProvider<GrayRuleMatcher> grayRuleMatcherProvider) {
    StellfluxCaffeineCacheFactory cacheFactory = cacheFactoryProvider.getIfAvailable();
    this.snapshotCache =
        cacheFactory == null
            ? null
            : cacheFactory.createCache(
                "stellnula-client-visible-snapshot", builder -> builder.maximumSize(10000));
    DataPlaneProperties properties = propertiesProvider.getIfAvailable();
    this.sensitiveAccessLabel =
        properties == null ? DEFAULT_SENSITIVE_ACCESS_LABEL : properties.sensitiveAccessLabel();
    this.metrics = metricsProvider.getIfAvailable();
    this.grayRuleMatcher =
        grayRuleMatcherProvider.getIfAvailable(() -> new GrayRuleMatcher(new ObjectMapper()));
  }

  /** 重建服务端配置缓存。 */
  public void rebuild(List<ConfigEntry> entries) {
    rebuild(entries, List.of());
  }

  /** 重建服务端配置缓存，包含已发布主配置和已激活灰度规则。 */
  public void rebuild(List<ConfigEntry> entries, List<ConfigGrayRule> grayRules) {
    rebuild(entries, grayRules, entries, grayRules, Integer.MAX_VALUE);
  }

  /** 重建服务端配置缓存，包含当前可见快照和有界增量事件窗口。 */
  public void rebuild(
      List<ConfigEntry> entries,
      List<ConfigGrayRule> grayRules,
      List<ConfigEntry> releaseEvents,
      List<ConfigGrayRule> grayRuleEvents,
      int eventWindowSize) {
    long latestEntryRevision = entries.stream().mapToLong(ConfigEntry::revision).max().orElse(0);
    long latestGrayRevision =
        grayRules.stream().mapToLong(ConfigGrayRule::effectiveRevision).max().orElse(0);
    long latestRevision = Math.max(latestEntryRevision, latestGrayRevision);
    List<ConfigCacheEvent> eventWindow =
        buildEventWindow(entries, releaseEvents, grayRuleEvents, eventWindowSize);
    long eventWindowMinRevision =
        eventWindow.stream().mapToLong(ConfigCacheEvent::revision).min().orElse(0);
    long minRevision = eventWindowMinRevision;
    state.set(
        new ConfigCacheState(
            latestRevision,
            minRevision,
            entries,
            grayRules,
            releaseEvents,
            grayRuleEvents,
            buildEntryIndex(entries),
            buildReleaseEventIndex(entries),
            buildGrayRuleIndex(grayRules),
            buildGrayRuleEventIndex(grayRuleEvents),
            eventWindow,
            eventWindowMinRevision,
            OffsetDateTime.now()));
    synchronized (revisionMonitor) {
      revisionMonitor.notifyAll();
    }
  }

  /** 增量合并配置发布和灰度事件，并原子替换内存索引。 */
  public boolean applyIncremental(
      List<ConfigEntry> releaseEvents,
      List<ConfigGrayRule> grayRuleEvents,
      int eventWindowSize,
      long expectedRevision) {
    if (releaseEvents.isEmpty() && grayRuleEvents.isEmpty()) {
      return false;
    }
    ConfigCacheState current = state.get();
    List<ConfigEntry> entries = mergeEntries(current.currentEntries(), releaseEvents);
    List<ConfigGrayRule> grayRules = mergeGrayRules(current.currentGrayRules(), grayRuleEvents);
    List<ConfigEntry> releaseEventWindow =
        mergeEventWindow(current.releaseEventWindow(), releaseEvents, eventWindowSize);
    List<ConfigGrayRule> grayRuleEventWindow =
        mergeGrayRuleEventWindow(current.grayRuleEventWindow(), grayRuleEvents, eventWindowSize);
    List<ConfigCacheEvent> eventWindow =
        buildEventWindow(entries, releaseEventWindow, grayRuleEventWindow, eventWindowSize);
    long eventWindowMinRevision =
        eventWindow.stream().mapToLong(ConfigCacheEvent::revision).min().orElse(0);
    long latestRevision =
        Math.max(
            expectedRevision,
            Math.max(
                entries.stream().mapToLong(ConfigEntry::revision).max().orElse(0),
                grayRules.stream().mapToLong(ConfigGrayRule::effectiveRevision).max().orElse(0)));
    state.set(
        new ConfigCacheState(
            latestRevision,
            eventWindowMinRevision,
            entries,
            grayRules,
            releaseEventWindow,
            grayRuleEventWindow,
            buildEntryIndex(entries),
            buildReleaseEventIndex(releaseEventWindow),
            buildGrayRuleIndex(grayRules),
            buildGrayRuleEventIndex(grayRuleEventWindow),
            eventWindow,
            eventWindowMinRevision,
            OffsetDateTime.now()));
    synchronized (revisionMonitor) {
      revisionMonitor.notifyAll();
    }
    return true;
  }

  /** 获取当前缓存最新 revision。 */
  public long latestRevision() {
    return state.get().latestRevision();
  }

  /** 获取当前缓存增量窗口最小 revision。 */
  public long minRevision() {
    return state.get().minRevision();
  }

  /** 获取当前缓存加载时间。 */
  public OffsetDateTime loadedAt() {
    return state.get().loadedAt();
  }

  /** 获取当前主配置记录数量。 */
  public int entryCount() {
    return state.get().currentEntries().size();
  }

  /** 获取当前灰度规则记录数量。 */
  public int grayRuleCount() {
    return state.get().currentGrayRules().size();
  }

  /** 获取符合客户端上下文的全量快照。 */
  public ConfigSnapshot snapshot(ClientContext clientContext) {
    ConfigCacheState current = state.get();
    ClientContext context = clientContext.normalize();
    String snapshotKey = snapshotKey(context, current);
    ConfigSnapshot cachedSnapshot =
        snapshotCache == null ? null : snapshotCache.getIfPresent(snapshotKey);
    if (cachedSnapshot != null) {
      return cachedSnapshot;
    }
    List<ConfigEntry> entries = selectEntries(context, current);
    ConfigSnapshot snapshot =
        new ConfigSnapshot(current.latestRevision(), checksum(entries), entries);
    if (snapshotCache != null) {
      snapshotCache.put(snapshotKey, snapshot);
    }
    return snapshot;
  }

  /** 获取指定 revision 之后的配置变化。 */
  public ConfigDelta delta(ClientContext clientContext, long fromRevision) {
    ConfigCacheState current = state.get();
    ConfigSnapshot snapshot = snapshot(clientContext);
    if (isTooOld(fromRevision)) {
      return new ConfigDelta(
          fromRevision,
          current.latestRevision(),
          snapshot.checksum(),
          List.of(),
          true,
          "EVENT_WINDOW_EXPIRED");
    }
    ClientContext context = clientContext.normalize();
    List<ConfigChange> changeList = selectEventChanges(context, snapshot, current, fromRevision);
    if (metrics != null) {
      metrics.recordDeltaHit(changeList.size());
    }
    return new ConfigDelta(fromRevision, current.latestRevision(), snapshot.checksum(), changeList);
  }

  /** 判断客户端 revision 是否已经落后于服务端保留窗口。 */
  public boolean isTooOld(long revision) {
    ConfigCacheState current = state.get();
    return revision > 0 && current.minRevision() > 0 && revision < current.minRevision() - 1;
  }

  /** 等待配置 revision 变化。 */
  public long waitForRevisionChange(long currentRevision, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    synchronized (revisionMonitor) {
      while (state.get().latestRevision() <= currentRevision) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          break;
        }
        revisionMonitor.wait(remaining);
      }
      return state.get().latestRevision();
    }
  }

  private List<ConfigEntry> selectEntries(ClientContext context, ConfigCacheState current) {
    Map<String, ScoredEntry> selected = new LinkedHashMap<>();
    for (ConfigEntry entry : candidateEntries(context, current)) {
      int score = score(context, entry);
      if (score < 0) {
        continue;
      }
      ConfigEntry visibleEntry = protectSensitive(context, applyGrayRule(context, entry, current));
      selected.merge(
          entry.configId(),
          new ScoredEntry(score, visibleEntry),
          (left, right) -> compare(left, right) >= 0 ? left : right);
    }
    return selected.values().stream()
        .map(ScoredEntry::entry)
        .sorted(Comparator.comparing(ConfigEntry::configKey))
        .toList();
  }

  private List<ConfigEntry> candidateEntries(ClientContext context, ConfigCacheState current) {
    return candidateEntries(context, current.entriesByBucket());
  }

  private List<ConfigEntry> candidateEntries(
      ClientContext context, Map<ConfigBucketKey, List<ConfigEntry>> index) {
    List<ConfigEntry> publicEntries =
        index.getOrDefault(
            new ConfigBucketKey(
                context.env(), context.namespaceCode(), context.groupCode(), "PUBLIC", "*"),
            List.of());
    List<ConfigEntry> applicationEntries =
        index.getOrDefault(
            new ConfigBucketKey(
                context.env(),
                context.namespaceCode(),
                context.groupCode(),
                "APPLICATION",
                context.appId()),
            List.of());
    if (publicEntries.isEmpty()) {
      return applicationEntries;
    }
    if (applicationEntries.isEmpty()) {
      return publicEntries;
    }
    return Stream.concat(publicEntries.stream(), applicationEntries.stream()).toList();
  }

  private int compare(ScoredEntry left, ScoredEntry right) {
    int scoreCompare = Integer.compare(left.score(), right.score());
    if (scoreCompare != 0) {
      return scoreCompare;
    }
    return Long.compare(left.entry().revision(), right.entry().revision());
  }

  private int score(ClientContext context, ConfigEntry entry) {
    if (!context.env().equals(entry.scope().env())) {
      return -1;
    }
    if (!context.namespaceCode().equals(entry.namespaceCode())) {
      return -1;
    }
    if (!context.groupCode().equals(entry.groupCode())) {
      return -1;
    }
    if (!isVisibleOwner(context, entry)) {
      return -1;
    }
    if (!isSubscribed(context, entry)) {
      return -1;
    }
    boolean exact =
        context.region().equals(entry.scope().region())
            && context.zone().equals(entry.scope().zone())
            && context.cluster().equals(entry.scope().cluster());
    if ("EXACT".equals(entry.scope().scopeMode())) {
      return exact ? 100 : -1;
    }
    int score = 0;
    score += scopeScore(context.region(), entry.scope().region(), 40);
    score += scopeScore(context.zone(), entry.scope().zone(), 30);
    score += scopeScore(context.cluster(), entry.scope().cluster(), 20);
    return score;
  }

  private ConfigEntry applyGrayRule(
      ClientContext context, ConfigEntry baseEntry, ConfigCacheState current) {
    Optional<ConfigGrayRule> matchedRule =
        current
            .grayRulesByConfigScope()
            .getOrDefault(
                new GrayRuleKey(baseEntry.configId(), baseEntry.scope().scopeId()), List.of())
            .stream()
            .filter(rule -> matchesGrayRule(context, rule))
            .findFirst();
    if (matchedRule.isPresent() && metrics != null) {
      metrics.recordGrayHit(matchedRule.get());
    }
    return matchedRule.map(baseEntry::withGrayRule).orElse(baseEntry);
  }

  private List<ConfigChange> selectEventChanges(
      ClientContext context, ConfigSnapshot snapshot, ConfigCacheState current, long fromRevision) {
    Map<String, ConfigEntry> currentEntries = new LinkedHashMap<>();
    snapshot.entries().forEach(entry -> currentEntries.put(entry.configId(), entry));
    List<ConfigChange> changes = new ArrayList<>();
    for (ConfigCacheEvent event : current.eventWindow()) {
      if (event.revision() <= fromRevision) {
        continue;
      }
      Optional<ConfigChange> change = selectEventChange(context, currentEntries, current, event);
      change.ifPresent(changes::add);
    }
    return List.copyOf(changes);
  }

  private Optional<ConfigChange> selectEventChange(
      ClientContext context,
      Map<String, ConfigEntry> currentEntries,
      ConfigCacheState current,
      ConfigCacheEvent event) {
    ConfigEntry eventEntry = event.entry();
    int score = score(context, eventEntry);
    if (score < 0) {
      return Optional.empty();
    }
    if (event.type() == ChangeType.DELETE) {
      return Optional.of(
          new ConfigChange(ChangeType.DELETE, protectSensitive(context, eventEntry)));
    }
    if (event.type() == ChangeType.GRAY_CHANGED) {
      return selectGrayEventChange(context, currentEntries, current, eventEntry);
    }
    return Optional.of(new ConfigChange(ChangeType.UPSERT, protectSensitive(context, eventEntry)));
  }

  private Optional<ConfigChange> selectGrayEventChange(
      ClientContext context,
      Map<String, ConfigEntry> currentEntries,
      ConfigCacheState current,
      ConfigEntry eventEntry) {
    if ("ENDED".equals(eventEntry.matchedType())) {
      ConfigEntry currentEntry = currentEntries.get(eventEntry.configId());
      return currentEntry == null
          ? Optional.empty()
          : Optional.of(new ConfigChange(ChangeType.GRAY_CHANGED, currentEntry));
    }
    Optional<ConfigGrayRule> eventRule =
        current
            .grayRuleEventsByConfigScope()
            .getOrDefault(
                new GrayRuleKey(eventEntry.configId(), eventEntry.scope().scopeId()), List.of())
            .stream()
            .filter(rule -> rule.effectiveRevision() == eventEntry.revision())
            .findFirst();
    if (eventRule.isPresent() && matchesGrayRule(context, eventRule.get())) {
      return Optional.of(
          new ConfigChange(
              ChangeType.GRAY_CHANGED,
              protectSensitive(context, eventEntry.withGrayRule(eventRule.get()))));
    }
    return Optional.empty();
  }

  private ConfigEntry protectSensitive(ClientContext context, ConfigEntry entry) {
    if (!entry.encrypted() || hasSensitiveAccess(context)) {
      return entry;
    }
    return entry.withValue(MASKED_VALUE);
  }

  private boolean hasSensitiveAccess(ClientContext context) {
    return "true".equalsIgnoreCase(context.labels().getOrDefault(sensitiveAccessLabel, "false"));
  }

  private boolean matchesGrayRule(ClientContext context, ConfigGrayRule rule) {
    return grayRuleMatcher.matches(context, rule, OffsetDateTime.now());
  }

  private int ruleTypePriority(ConfigGrayRule rule) {
    return switch (rule.ruleType()) {
      case "IP" -> 0;
      case "TAG" -> 1;
      case "PERCENTAGE" -> 2;
      default -> 3;
    };
  }

  private boolean isVisibleOwner(ClientContext context, ConfigEntry entry) {
    if ("PUBLIC".equals(entry.ownerType())) {
      return true;
    }
    return "APPLICATION".equals(entry.ownerType()) && context.appId().equals(entry.ownerId());
  }

  private boolean isSubscribed(ClientContext context, ConfigEntry entry) {
    if (context.subscriptions().isEmpty()) {
      return true;
    }
    return context.subscriptions().stream().anyMatch(subscription -> subscription.matches(entry));
  }

  private int scopeScore(String requested, String candidate, int exactScore) {
    if (requested.equals(candidate)) {
      return exactScore;
    }
    if (DEFAULT_SCOPE.equals(candidate)) {
      return 1;
    }
    return -1000;
  }

  private String checksum(List<ConfigEntry> entries) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (ConfigEntry entry : entries) {
        update(digest, entry.configId());
        update(digest, entry.configKey());
        update(digest, entry.value());
        update(digest, Long.toString(entry.version()));
        update(digest, Long.toString(entry.revision()));
        update(digest, entry.matchedType());
        update(digest, entry.matchedGrayName());
      }
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private void update(MessageDigest digest, String value) {
    digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }

  private long minPositive(long left, long right) {
    if (left <= 0) {
      return right;
    }
    if (right <= 0) {
      return left;
    }
    return Math.min(left, right);
  }

  private List<ConfigEntry> mergeEntries(
      List<ConfigEntry> currentEntries, List<ConfigEntry> releaseEvents) {
    Map<String, ConfigEntry> merged = new LinkedHashMap<>();
    currentEntries.forEach(entry -> merged.put(entryKey(entry), entry));
    releaseEvents.stream()
        .sorted(Comparator.comparingLong(ConfigEntry::revision))
        .forEach(
            event ->
                merged.merge(
                    entryKey(event),
                    event,
                    (current, next) -> next.revision() >= current.revision() ? next : current));
    return List.copyOf(merged.values());
  }

  private List<ConfigGrayRule> mergeGrayRules(
      List<ConfigGrayRule> currentGrayRules, List<ConfigGrayRule> grayRuleEvents) {
    Map<String, ConfigGrayRule> merged = new LinkedHashMap<>();
    currentGrayRules.forEach(rule -> merged.put(grayRuleKey(rule), rule));
    grayRuleEvents.stream()
        .sorted(Comparator.comparingLong(ConfigGrayRule::effectiveRevision))
        .forEach(
            event ->
                merged.merge(
                    grayRuleKey(event),
                    event,
                    (current, next) ->
                        next.effectiveRevision() >= current.effectiveRevision() ? next : current));
    return List.copyOf(merged.values());
  }

  private List<ConfigEntry> mergeEventWindow(
      List<ConfigEntry> currentEvents, List<ConfigEntry> newEvents, int eventWindowSize) {
    return Stream.concat(currentEvents.stream(), newEvents.stream())
        .sorted(Comparator.comparingLong(ConfigEntry::revision).reversed())
        .limit(eventWindowSize)
        .sorted(Comparator.comparingLong(ConfigEntry::revision))
        .toList();
  }

  private List<ConfigGrayRule> mergeGrayRuleEventWindow(
      List<ConfigGrayRule> currentEvents, List<ConfigGrayRule> newEvents, int eventWindowSize) {
    return Stream.concat(currentEvents.stream(), newEvents.stream())
        .sorted(Comparator.comparingLong(ConfigGrayRule::effectiveRevision).reversed())
        .limit(eventWindowSize)
        .sorted(Comparator.comparingLong(ConfigGrayRule::effectiveRevision))
        .toList();
  }

  private String entryKey(ConfigEntry entry) {
    return entry.configId() + ":" + entry.scope().scopeId();
  }

  private String grayRuleKey(ConfigGrayRule rule) {
    if (rule.id() > 0) {
      return Long.toString(rule.id());
    }
    return rule.configId() + ":" + rule.scopeId() + ":" + rule.grayName();
  }

  private Map<ConfigBucketKey, List<ConfigEntry>> buildEntryIndex(List<ConfigEntry> entries) {
    Map<ConfigBucketKey, List<ConfigEntry>> index = new HashMap<>();
    for (ConfigEntry entry : entries) {
      if (entry.deleted()) {
        continue;
      }
      ConfigBucketKey key =
          new ConfigBucketKey(
              entry.scope().env(),
              entry.namespaceCode(),
              entry.groupCode(),
              entry.ownerType(),
              "PUBLIC".equals(entry.ownerType()) ? "*" : entry.ownerId());
      index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
    }
    return immutableListMap(index);
  }

  private Map<ConfigBucketKey, List<ConfigEntry>> buildReleaseEventIndex(
      List<ConfigEntry> entries) {
    Map<ConfigBucketKey, List<ConfigEntry>> index = new HashMap<>();
    for (ConfigEntry entry : entries) {
      ConfigBucketKey key =
          new ConfigBucketKey(
              entry.scope().env(),
              entry.namespaceCode(),
              entry.groupCode(),
              entry.ownerType(),
              "PUBLIC".equals(entry.ownerType()) ? "*" : entry.ownerId());
      index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
    }
    return immutableListMap(index);
  }

  private Map<GrayRuleKey, List<ConfigGrayRule>> buildGrayRuleIndex(
      List<ConfigGrayRule> grayRules) {
    Map<GrayRuleKey, List<ConfigGrayRule>> index = new HashMap<>();
    for (ConfigGrayRule rule : grayRules) {
      index
          .computeIfAbsent(
              new GrayRuleKey(rule.configId(), rule.scopeId()), ignored -> new ArrayList<>())
          .add(rule);
    }
    for (List<ConfigGrayRule> rules : index.values()) {
      rules.sort(
          Comparator.comparingInt(ConfigGrayRule::priority)
              .thenComparingInt(this::ruleTypePriority)
              .thenComparingLong(ConfigGrayRule::id));
    }
    return immutableListMap(index);
  }

  private Map<GrayRuleKey, List<ConfigGrayRule>> buildGrayRuleEventIndex(
      List<ConfigGrayRule> grayRules) {
    Map<GrayRuleKey, List<ConfigGrayRule>> index = new HashMap<>();
    for (ConfigGrayRule rule : grayRules) {
      index
          .computeIfAbsent(
              new GrayRuleKey(rule.configId(), rule.scopeId()), ignored -> new ArrayList<>())
          .add(rule);
    }
    return immutableListMap(index);
  }

  private List<ConfigCacheEvent> buildEventWindow(
      List<ConfigEntry> entries,
      List<ConfigEntry> releaseEvents,
      List<ConfigGrayRule> grayRuleEvents,
      int eventWindowSize) {
    List<ConfigCacheEvent> events = new ArrayList<>();
    releaseEvents.stream()
        .map(
            entry ->
                new ConfigCacheEvent(
                    entry.revision(),
                    entry.deleted() ? ChangeType.DELETE : ChangeType.UPSERT,
                    entry))
        .forEach(events::add);
    grayRuleEvents.stream().map(rule -> toGrayChangedEvent(entries, rule)).forEach(events::add);
    return events.stream()
        .sorted(Comparator.comparingLong(ConfigCacheEvent::revision).reversed())
        .limit(eventWindowSize)
        .sorted(Comparator.comparingLong(ConfigCacheEvent::revision))
        .toList();
  }

  private ConfigCacheEvent toGrayChangedEvent(List<ConfigEntry> entries, ConfigGrayRule rule) {
    ConfigEntry baseEntry =
        entries.stream()
            .filter(entry -> entry.configId().equals(rule.configId()))
            .filter(entry -> entry.scope().scopeId() == rule.scopeId())
            .findFirst()
            .orElse(null);
    if (baseEntry == null) {
      baseEntry =
          new ConfigEntry(
              rule.configId(),
              rule.configId(),
              "APPLICATION",
              "",
              DEFAULT_SCOPE,
              DEFAULT_SCOPE,
              "KV",
              "",
              rule.grayVersion(),
              rule.effectiveRevision(),
              false,
              new ConfigScope(
                  rule.scopeId(),
                  DEFAULT_SCOPE,
                  DEFAULT_SCOPE,
                  DEFAULT_SCOPE,
                  DEFAULT_SCOPE,
                  "EXACT"));
    }
    ConfigEntry eventEntry =
        new ConfigEntry(
            baseEntry.configId(),
            baseEntry.configKey(),
            baseEntry.ownerType(),
            baseEntry.ownerId(),
            baseEntry.namespaceCode(),
            baseEntry.groupCode(),
            baseEntry.contentType(),
            baseEntry.value(),
            baseEntry.version(),
            rule.effectiveRevision(),
            baseEntry.encrypted(),
            baseEntry.scope(),
            false,
            rule.status(),
            rule.id(),
            rule.grayName(),
            rule.grayVersion());
    return new ConfigCacheEvent(rule.effectiveRevision(), ChangeType.GRAY_CHANGED, eventEntry);
  }

  private <K, V> Map<K, List<V>> immutableListMap(Map<K, List<V>> source) {
    Map<K, List<V>> copy = new HashMap<>();
    source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
    return Map.copyOf(copy);
  }

  private String snapshotKey(ClientContext context, ConfigCacheState current) {
    return String.join(
        "|",
        Long.toString(current.latestRevision()),
        context.appId(),
        context.clientId(),
        context.env(),
        context.region(),
        context.zone(),
        context.cluster(),
        context.namespaceCode(),
        context.groupCode(),
        context.clientIp() == null ? "" : context.clientIp(),
        labelsKey(context.labels()),
        subscriptionsKey(context),
        grayTimeBucketKey(current));
  }

  private String labelsKey(Map<String, String> labels) {
    if (labels.isEmpty()) {
      return "";
    }
    TreeMap<String, String> sortedLabels = new TreeMap<>(labels);
    StringBuilder builder = new StringBuilder();
    sortedLabels.forEach((key, value) -> builder.append(key).append('=').append(value).append(','));
    return builder.toString();
  }

  private String subscriptionsKey(ClientContext context) {
    if (context.subscriptions().isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    context.subscriptions().stream()
        .map(subscription -> subscription.normalize())
        .sorted(
            Comparator.comparing(
                    io.github.stellnula.domain.ClientSubscriptionFilter::subscriptionType)
                .thenComparing(io.github.stellnula.domain.ClientSubscriptionFilter::groupCode)
                .thenComparing(
                    io.github.stellnula.domain.ClientSubscriptionFilter::subscriptionKey))
        .forEach(
            subscription ->
                builder
                    .append(subscription.subscriptionType())
                    .append(':')
                    .append(subscription.groupCode())
                    .append(':')
                    .append(subscription.subscriptionKey())
                    .append(','));
    return builder.toString();
  }

  private String grayTimeBucketKey(ConfigCacheState current) {
    boolean hasTimeWindowRule =
        current.grayRulesByConfigScope().values().stream()
            .flatMap(List::stream)
            .anyMatch(
                rule ->
                    "ACTIVE".equals(rule.status())
                        && (rule.startTime() != null || rule.endTime() != null));
    if (!hasTimeWindowRule) {
      return "";
    }
    return Long.toString(System.currentTimeMillis() / 1000 / GRAY_TIME_BUCKET_SECONDS);
  }

  private record ScoredEntry(int score, ConfigEntry entry) {}
}

package io.github.stellnula.cache;

import io.github.stellnula.domain.ConfigCacheEvent;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayRule;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

record ConfigCacheState(
    long latestRevision,
    long minRevision,
    List<ConfigEntry> currentEntries,
    List<ConfigGrayRule> currentGrayRules,
    List<ConfigEntry> releaseEventWindow,
    List<ConfigGrayRule> grayRuleEventWindow,
    Map<ConfigBucketKey, List<ConfigEntry>> entriesByBucket,
    Map<ConfigBucketKey, List<ConfigEntry>> releaseEventsByBucket,
    Map<GrayRuleKey, List<ConfigGrayRule>> grayRulesByConfigScope,
    Map<GrayRuleKey, List<ConfigGrayRule>> grayRuleEventsByConfigScope,
    List<ConfigCacheEvent> eventWindow,
    long eventWindowMinRevision,
    OffsetDateTime loadedAt) {

  ConfigCacheState {
    currentEntries = List.copyOf(currentEntries);
    currentGrayRules = List.copyOf(currentGrayRules);
    releaseEventWindow = List.copyOf(releaseEventWindow);
    grayRuleEventWindow = List.copyOf(grayRuleEventWindow);
    entriesByBucket = Map.copyOf(entriesByBucket);
    releaseEventsByBucket = Map.copyOf(releaseEventsByBucket);
    grayRulesByConfigScope = Map.copyOf(grayRulesByConfigScope);
    grayRuleEventsByConfigScope = Map.copyOf(grayRuleEventsByConfigScope);
    eventWindow = List.copyOf(eventWindow);
  }

  static ConfigCacheState empty() {
    return new ConfigCacheState(
        0,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        List.of(),
        0,
        OffsetDateTime.now());
  }

  record ConfigBucketKey(
      String env, String namespaceCode, String groupCode, String ownerType, String ownerId) {}

  record GrayRuleKey(String configId, long scopeId) {}
}

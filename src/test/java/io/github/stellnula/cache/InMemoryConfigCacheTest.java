package io.github.stellnula.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ClientSubscriptionFilter;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayRule;
import io.github.stellnula.domain.ConfigScope;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryConfigCacheTest {

  @Test
  void shouldSelectApplicationAndPublicConfigsWithinSameEnvironment() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    cache.rebuild(
        List.of(
            entry(
                "app-timeout",
                "timeout",
                "APPLICATION",
                "order-service",
                "prod",
                "sg",
                "sg-a",
                "default",
                1),
            entry(
                "public-log",
                "logging.level",
                "PUBLIC",
                "platform",
                "prod",
                "default",
                "default",
                "default",
                2),
            entry(
                "other-app",
                "thread.pool",
                "APPLICATION",
                "payment-service",
                "prod",
                "sg",
                "sg-a",
                "default",
                3)));

    var snapshot =
        cache.snapshot(
            new ClientContext(
                "order-service", "client-1", "prod", "sg", "sg-a", "default", "default"));

    assertThat(snapshot.entries())
        .extracting(ConfigEntry::configId)
        .containsExactly("public-log", "app-timeout");
    assertThat(snapshot.revision()).isEqualTo(3);
  }

  @Test
  void shouldNotFallbackAcrossEnvironment() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    cache.rebuild(
        List.of(
            entry(
                "prod-config",
                "timeout",
                "APPLICATION",
                "order-service",
                "prod",
                "sg",
                "sg-a",
                "default",
                1),
            entry(
                "test-config",
                "timeout",
                "APPLICATION",
                "order-service",
                "test",
                "sg",
                "sg-a",
                "default",
                2)));

    var snapshot =
        cache.snapshot(
            new ClientContext(
                "order-service", "client-1", "prod", "sg", "sg-a", "default", "default"));

    assertThat(snapshot.entries()).extracting(ConfigEntry::configId).containsExactly("prod-config");
  }

  @Test
  void shouldPreferExactScopeOverDefaultScope() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    cache.rebuild(
        List.of(
            entry(
                "timeout",
                "timeout",
                "APPLICATION",
                "order-service",
                "prod",
                "default",
                "default",
                "default",
                1),
            entry(
                "timeout",
                "timeout",
                "APPLICATION",
                "order-service",
                "prod",
                "sg",
                "sg-a",
                "gray",
                2)));

    var snapshot =
        cache.snapshot(
            new ClientContext(
                "order-service", "client-1", "prod", "sg", "sg-a", "gray", "default"));

    assertThat(snapshot.entries()).singleElement().extracting(ConfigEntry::revision).isEqualTo(2L);
  }

  @Test
  void shouldApplyMatchedGrayRuleOverBaseConfig() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry baseEntry =
        entry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            1);
    cache.rebuild(
        List.of(baseEntry),
        List.of(
            new ConfigGrayRule(
                100,
                "timeout",
                baseEntry.scope().scopeId(),
                "gray-by-label",
                "TAG",
                "{\"type\":\"TAG\",\"op\":\"EQ\",\"key\":\"env\",\"value\":\"gray\"}",
                "value-gray",
                2,
                10,
                "md5:gray",
                10,
                "ACTIVE",
                OffsetDateTime.now(),
                null)));

    var snapshot =
        cache.snapshot(
            new ClientContext(
                "order-service",
                "client-1",
                "prod",
                "sg",
                "sg-a",
                "default",
                "default",
                "10.0.0.1",
                Map.of("env", "gray")));

    assertThat(snapshot.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.value()).isEqualTo("value-gray");
              assertThat(entry.revision()).isEqualTo(10);
              assertThat(entry.matchedType()).isEqualTo("GRAY");
              assertThat(entry.matchedGrayName()).isEqualTo("gray-by-label");
            });
  }

  @Test
  void shouldRespectGrayRuleEffectiveWindow() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry baseEntry =
        entry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            1);
    cache.rebuild(
        List.of(baseEntry),
        List.of(
            new ConfigGrayRule(
                100,
                "timeout",
                baseEntry.scope().scopeId(),
                "gray-by-label",
                "TAG",
                "{\"type\":\"TAG\",\"op\":\"EQ\",\"key\":\"env\",\"value\":\"gray\"}",
                "value-gray",
                2,
                10,
                "md5:gray",
                10,
                "ACTIVE",
                OffsetDateTime.now().plusMinutes(1),
                OffsetDateTime.now().plusHours(1))));

    var snapshot =
        cache.snapshot(
            new ClientContext(
                "order-service",
                "client-1",
                "prod",
                "sg",
                "sg-a",
                "default",
                "default",
                "10.0.0.1",
                Map.of("env", "gray")));

    assertThat(snapshot.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.value()).isEqualTo("value-1");
              assertThat(entry.matchedType()).isEqualTo("BASE");
            });
  }

  @Test
  void shouldReturnDeleteChangeWhenLatestVisibleReleaseIsDeleted() {
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
                "",
                2,
                8,
                false,
                new ConfigScope(1, "prod", "sg", "sg-a", "default", "INHERITABLE"),
                true)));

    var delta =
        cache.delta(
            new ClientContext(
                "order-service", "client-1", "prod", "sg", "sg-a", "default", "default"),
            7);

    assertThat(delta.toRevision()).isEqualTo(8);
    assertThat(delta.changes())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.type()).isEqualTo(io.github.stellnula.domain.ChangeType.DELETE);
              assertThat(change.entry().configId()).isEqualTo("timeout");
              assertThat(change.entry().deleted()).isTrue();
            });
  }

  @Test
  void shouldReturnBaseConfigWhenGrayRuleEndedAfterClientRevision() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry baseEntry =
        entry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            1);
    cache.rebuild(
        List.of(baseEntry),
        List.of(
            new ConfigGrayRule(
                100,
                "timeout",
                baseEntry.scope().scopeId(),
                "gray-by-label",
                "TAG",
                "{\"type\":\"TAG\",\"op\":\"EQ\",\"key\":\"env\",\"value\":\"gray\"}",
                "value-gray",
                2,
                10,
                "md5:gray",
                10,
                "ENDED",
                OffsetDateTime.now(),
                OffsetDateTime.now())));

    var delta =
        cache.delta(
            new ClientContext(
                "order-service",
                "client-1",
                "prod",
                "sg",
                "sg-a",
                "default",
                "default",
                "10.0.0.1",
                Map.of("env", "gray")),
            9);

    assertThat(delta.toRevision()).isEqualTo(10);
    assertThat(delta.changes())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.type())
                  .isEqualTo(io.github.stellnula.domain.ChangeType.GRAY_CHANGED);
              assertThat(change.entry().value()).isEqualTo("value-1");
              assertThat(change.entry().matchedType()).isEqualTo("BASE");
            });
  }

  @Test
  void shouldReplayOrderedReleaseEventsWithinWindow() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry oldEvent =
        entry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            2);
    ConfigEntry newEvent =
        entry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            5);
    cache.rebuild(List.of(newEvent), List.of(), List.of(oldEvent, newEvent), List.of(), 100);

    var delta =
        cache.delta(
            new ClientContext(
                "order-service", "client-1", "prod", "sg", "sg-a", "default", "default"),
            1);

    assertThat(delta.changes())
        .extracting(change -> change.entry().revision())
        .containsExactly(2L, 5L);
  }

  @Test
  void shouldApplyIncrementalReleaseEventsWithoutFullRebuild() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry oldEntry =
        entry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            1);
    ConfigEntry newEntry =
        entry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            2);
    cache.rebuild(List.of(oldEntry), List.of(), List.of(oldEntry), List.of(), 100);

    boolean applied = cache.applyIncremental(List.of(newEntry), List.of(), 100, 2);

    var snapshot =
        cache.snapshot(
            new ClientContext(
                "order-service", "client-1", "prod", "sg", "sg-a", "default", "default"));
    var delta =
        cache.delta(
            new ClientContext(
                "order-service", "client-1", "prod", "sg", "sg-a", "default", "default"),
            1);

    assertThat(applied).isTrue();
    assertThat(snapshot.entries())
        .singleElement()
        .extracting(ConfigEntry::value)
        .isEqualTo("value-2");
    assertThat(delta.changes())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.type()).isEqualTo(io.github.stellnula.domain.ChangeType.UPSERT);
              assertThat(change.entry().revision()).isEqualTo(2);
            });
  }

  @Test
  void shouldApplyIncrementalDeleteEventWithoutVisibleSnapshotEntry() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry oldEntry =
        entry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            1);
    ConfigEntry deleteEntry =
        new ConfigEntry(
            "timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "default",
            "default",
            "KV",
            "",
            2,
            3,
            false,
            new ConfigScope(1, "prod", "sg", "sg-a", "default", "INHERITABLE"),
            true);
    cache.rebuild(List.of(oldEntry), List.of(), List.of(oldEntry), List.of(), 100);

    boolean applied = cache.applyIncremental(List.of(deleteEntry), List.of(), 100, 3);

    var context =
        new ClientContext("order-service", "client-1", "prod", "sg", "sg-a", "default", "default");
    assertThat(applied).isTrue();
    assertThat(cache.snapshot(context).entries()).isEmpty();
    assertThat(cache.delta(context, 2).changes())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.type()).isEqualTo(io.github.stellnula.domain.ChangeType.DELETE);
              assertThat(change.entry().deleted()).isTrue();
            });
  }

  @Test
  void shouldFilterSnapshotByClientSubscriptions() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    cache.rebuild(
        List.of(
            entry(
                "app-timeout",
                "timeout",
                "APPLICATION",
                "order-service",
                "prod",
                "sg",
                "sg-a",
                "default",
                1),
            entry(
                "app-thread",
                "thread.pool",
                "APPLICATION",
                "order-service",
                "prod",
                "sg",
                "sg-a",
                "default",
                2),
            entry(
                "public-log",
                "logging.level",
                "PUBLIC",
                "platform",
                "prod",
                "default",
                "default",
                "default",
                3)));

    var snapshot =
        cache.snapshot(
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
                List.of(new ClientSubscriptionFilter("default", "CONFIG", "app-timeout"))));

    assertThat(snapshot.entries()).extracting(ConfigEntry::configId).containsExactly("app-timeout");
  }

  @Test
  void shouldFilterDeltaEventsByClientSubscriptions() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    ConfigEntry timeout =
        entry(
            "app-timeout",
            "timeout",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            2);
    ConfigEntry thread =
        entry(
            "app-thread",
            "thread.pool",
            "APPLICATION",
            "order-service",
            "prod",
            "sg",
            "sg-a",
            "default",
            3);
    cache.rebuild(List.of(timeout, thread), List.of(), List.of(timeout, thread), List.of(), 100);

    var delta =
        cache.delta(
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
            1);

    assertThat(delta.changes())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.entry().configId()).isEqualTo("app-timeout");
              assertThat(change.entry().revision()).isEqualTo(2);
            });
  }

  @Test
  void shouldFilterGovernanceRulesBySubscriptionType() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    cache.rebuild(
        List.of(
            new ConfigEntry(
                "route-rule",
                "route.order",
                "APPLICATION",
                "order-service",
                "governance",
                "service-governance",
                "KV",
                "value-1",
                1,
                1,
                false,
                new ConfigScope(1, "prod", "sg", "sg-a", "default", "INHERITABLE")),
            new ConfigEntry(
                "normal-config",
                "timeout",
                "APPLICATION",
                "order-service",
                "governance",
                "default",
                "KV",
                "value-2",
                2,
                2,
                false,
                new ConfigScope(2, "prod", "sg", "sg-a", "default", "INHERITABLE"))));

    var snapshot =
        cache.snapshot(
            new ClientContext(
                "order-service",
                "client-1",
                "prod",
                "sg",
                "sg-a",
                "default",
                "governance",
                "service-governance",
                "",
                Map.of(),
                List.of(
                    new ClientSubscriptionFilter("service-governance", "GOVERNANCE_RULE", "*"))));

    assertThat(snapshot.entries()).extracting(ConfigEntry::configId).containsExactly("route-rule");
  }

  @Test
  void shouldFilterSnapshotByGroupWithinSameNamespace() {
    InMemoryConfigCache cache = new InMemoryConfigCache();
    cache.rebuild(
        List.of(
            entryWithGroup("default-timeout", "timeout", "default", 1),
            entryWithGroup("payment-timeout", "timeout", "payment", 2)));

    var snapshot =
        cache.snapshot(
            new ClientContext(
                "order-service",
                "client-1",
                "prod",
                "sg",
                "sg-a",
                "default",
                "default",
                "payment"));

    assertThat(snapshot.entries())
        .extracting(ConfigEntry::configId)
        .containsExactly("payment-timeout");
  }

  private ConfigEntry entry(
      String configId,
      String key,
      String ownerType,
      String ownerId,
      String env,
      String region,
      String zone,
      String cluster,
      long revision) {
    return new ConfigEntry(
        configId,
        key,
        ownerType,
        ownerId,
        "default",
        "default",
        "KV",
        "value-" + revision,
        revision,
        revision,
        false,
        new ConfigScope(revision, env, region, zone, cluster, "INHERITABLE"));
  }

  private ConfigEntry entryWithGroup(String configId, String key, String groupCode, long revision) {
    return new ConfigEntry(
        configId,
        key,
        "APPLICATION",
        "order-service",
        "default",
        groupCode,
        "KV",
        "value-" + revision,
        revision,
        revision,
        false,
        new ConfigScope(revision, "prod", "sg", "sg-a", "default", "INHERITABLE"));
  }
}

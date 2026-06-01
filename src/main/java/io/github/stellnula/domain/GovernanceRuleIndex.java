package io.github.stellnula.domain;

public record GovernanceRuleIndex(
    String configId,
    long scopeId,
    long releaseId,
    long revision,
    String ownerType,
    String ownerId,
    String env,
    String region,
    String zone,
    String cluster,
    String ruleType,
    String targetService,
    String status,
    int priority) {}

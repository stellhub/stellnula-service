package io.github.stellnula.repository;

public record ClientSubscriptionState(
    String appId,
    String clientId,
    String env,
    String region,
    String zone,
    String cluster,
    String namespaceCode,
    String groupCode,
    String subscriptionType,
    String subscriptionKey,
    long currentRevision,
    String currentChecksum,
    String transport,
    String status) {}

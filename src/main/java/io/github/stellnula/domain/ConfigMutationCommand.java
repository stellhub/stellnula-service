package io.github.stellnula.domain;

public record ConfigMutationCommand(
    ConfigMutationAction action,
    String configId,
    String configName,
    String ownerType,
    String ownerId,
    String namespaceCode,
    String groupCode,
    String contentType,
    boolean sensitive,
    String description,
    String env,
    String region,
    String zone,
    String cluster,
    String scopeMode,
    String content,
    String operator,
    String reason) {}

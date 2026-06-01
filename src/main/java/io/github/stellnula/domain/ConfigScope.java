package io.github.stellnula.domain;

public record ConfigScope(
    long scopeId, String env, String region, String zone, String cluster, String scopeMode) {}

package io.github.stellnula.domain;

public record ConfigCacheEvent(long revision, ChangeType type, ConfigEntry entry) {}

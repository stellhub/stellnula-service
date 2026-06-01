package io.github.stellnula.domain;

public record RetryBackoffHint(
    long initialDelayMillis, long maxDelayMillis, double multiplier, double jitterRatio) {}

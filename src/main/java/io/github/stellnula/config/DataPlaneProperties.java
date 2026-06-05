package io.github.stellnula.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "stellnula.data-plane")
public record DataPlaneProperties(
    @NotBlank String region,
    @NotBlank String zone,
    @Min(1) int weight,
    @Min(1) long addressTtlSeconds,
    @Min(1) int watchTimeoutMillis,
    @Min(1) long heartbeatMillis,
    @Min(1000) long refreshIntervalMillis,
    @Min(1) int maxConcurrentWatch,
    @Min(1) int maxRequestLabels,
    @Min(1) int maxConfigContentBytes,
    @Min(1) long nodeExpireMillis,
    @Min(1) long nodeDrainMillis,
    @Min(100) int nodeProbeTimeoutMillis,
    @Min(1) int nodeProbeFailureThreshold,
    @Min(0) long gracefulShutdownWaitMillis,
    @Min(1000) long cacheRefreshBackoffMillis,
    @Min(1000) long cacheRefreshMaxBackoffMillis,
    @Min(1000) long cacheStaleThresholdMillis,
    @Min(1) int eventWindowSize,
    @NotBlank String currentApiVersion,
    @NotBlank String minApiVersion,
    @NotBlank String minSdkVersion,
    @NotBlank String serverVersion,
    @Min(1) int defaultPageSize,
    @Min(1) int maxPageSize,
    @Min(1) int maxResponsePayloadBytes,
    @Min(1) int compressionThresholdBytes,
    @Min(1) int largeFileReferenceThresholdBytes,
    @Min(1) long clientRetryInitialMillis,
    @Min(1) long clientRetryMaxMillis,
    double clientRetryMultiplier,
    double clientRetryJitterRatio,
    @NotBlank String sensitiveEncryptionKey,
    @NotBlank String sensitiveAccessLabel) {}

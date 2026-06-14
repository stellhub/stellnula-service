package io.github.stellnula.domain;

import java.time.OffsetDateTime;

public record ControlPlaneAppConfigRecord(
    String configId,
    String appId,
    String configName,
    String description,
    String env,
    String cluster,
    String group,
    String format,
    long version,
    String releaseStatus,
    String content,
    String updatedBy,
    OffsetDateTime updatedAt,
    OffsetDateTime publishedAt,
    boolean formatLocked) {}

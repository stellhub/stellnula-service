package io.github.stellnula.domain;

import java.time.OffsetDateTime;

public record ConfigGrayRecord(
    long id,
    String configId,
    long scopeId,
    String grayName,
    String ruleType,
    String grayRules,
    String configValue,
    long grayVersion,
    long effectiveRevision,
    String checksum,
    int priority,
    String status,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    OffsetDateTime updatedAt) {}

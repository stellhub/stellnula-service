package io.github.stellnula.domain;

import java.time.OffsetDateTime;

public record ConfigGrayMutationResult(
    long grayRuleId,
    String configId,
    long scopeId,
    String grayName,
    long grayVersion,
    long effectiveRevision,
    String status,
    String checksum,
    OffsetDateTime updatedAt) {}

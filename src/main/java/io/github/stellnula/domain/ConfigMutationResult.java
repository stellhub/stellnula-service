package io.github.stellnula.domain;

import java.time.OffsetDateTime;

public record ConfigMutationResult(
    String configId,
    long scopeId,
    String releaseNo,
    long version,
    long revision,
    String releaseStatus,
    String checksum,
    OffsetDateTime releasedAt) {}

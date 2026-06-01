package io.github.stellnula.domain;

import java.time.OffsetDateTime;

public record ConfigGrayMutationCommand(
    String configId,
    String grayName,
    String env,
    String region,
    String zone,
    String cluster,
    String ruleType,
    String grayRules,
    String configValue,
    int priority,
    String status,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    String operator,
    String reason) {}

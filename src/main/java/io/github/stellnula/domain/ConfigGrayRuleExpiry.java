package io.github.stellnula.domain;

import java.time.OffsetDateTime;

public record ConfigGrayRuleExpiry(
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
    OffsetDateTime startTime,
    OffsetDateTime endTime) {}

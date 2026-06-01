package io.github.stellnula.repository;

import java.time.OffsetDateTime;

public record ClientSnapshotState(
    String appId,
    String clientId,
    String env,
    String region,
    String zone,
    String cluster,
    String namespaceCode,
    String groupCode,
    long localRevision,
    String localChecksum,
    boolean localFileLoaded,
    OffsetDateTime lastSuccessSyncAt) {}

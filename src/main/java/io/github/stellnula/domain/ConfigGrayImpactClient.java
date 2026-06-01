package io.github.stellnula.domain;

import java.time.OffsetDateTime;
import java.util.Map;

public record ConfigGrayImpactClient(
    String appId,
    String clientId,
    String env,
    String region,
    String zone,
    String cluster,
    String namespaceCode,
    String groupCode,
    String clientIp,
    String hostName,
    String sdkVersion,
    Map<String, String> labels,
    OffsetDateTime lastSeenAt) {

  public ConfigGrayImpactClient {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }

  public ClientContext toClientContext() {
    return new ClientContext(
        appId,
        clientId,
        env,
        region,
        zone,
        cluster,
        namespaceCode,
        groupCode,
        clientIp,
        labels,
        java.util.List.of());
  }
}

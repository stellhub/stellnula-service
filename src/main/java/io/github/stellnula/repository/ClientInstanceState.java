package io.github.stellnula.repository;

import java.util.Map;

public record ClientInstanceState(
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
    Map<String, String> metadata,
    String status) {

  public ClientInstanceState {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}

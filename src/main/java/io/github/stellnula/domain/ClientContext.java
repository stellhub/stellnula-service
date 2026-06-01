package io.github.stellnula.domain;

import java.util.List;
import java.util.Map;

public record ClientContext(
    String appId,
    String clientId,
    String env,
    String region,
    String zone,
    String cluster,
    String namespaceCode,
    String groupCode,
    String clientIp,
    Map<String, String> labels,
    List<ClientSubscriptionFilter> subscriptions) {

  public ClientContext(
      String appId,
      String clientId,
      String env,
      String region,
      String zone,
      String cluster,
      String namespaceCode) {
    this(
        appId,
        clientId,
        env,
        region,
        zone,
        cluster,
        namespaceCode,
        "default",
        "",
        Map.of(),
        List.of());
  }

  public ClientContext(
      String appId,
      String clientId,
      String env,
      String region,
      String zone,
      String cluster,
      String namespaceCode,
      String groupCode) {
    this(
        appId,
        clientId,
        env,
        region,
        zone,
        cluster,
        namespaceCode,
        groupCode,
        "",
        Map.of(),
        List.of());
  }

  public ClientContext(
      String appId,
      String clientId,
      String env,
      String region,
      String zone,
      String cluster,
      String namespaceCode,
      String clientIp,
      Map<String, String> labels) {
    this(
        appId,
        clientId,
        env,
        region,
        zone,
        cluster,
        namespaceCode,
        "default",
        clientIp,
        labels,
        List.of());
  }

  public ClientContext(
      String appId,
      String clientId,
      String env,
      String region,
      String zone,
      String cluster,
      String namespaceCode,
      String clientIp,
      Map<String, String> labels,
      List<ClientSubscriptionFilter> subscriptions) {
    this(
        appId,
        clientId,
        env,
        region,
        zone,
        cluster,
        namespaceCode,
        "default",
        clientIp,
        labels,
        subscriptions);
  }

  public ClientContext {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
    subscriptions =
        subscriptions == null
            ? List.of()
            : subscriptions.stream().map(ClientSubscriptionFilter::normalize).toList();
  }

  public ClientContext normalize() {
    return new ClientContext(
        appId,
        clientId,
        env,
        defaultValue(region),
        defaultValue(zone),
        defaultValue(cluster),
        defaultValue(namespaceCode),
        defaultValue(groupCode),
        clientIp == null ? "" : clientIp,
        labels,
        subscriptions);
  }

  private static String defaultValue(String value) {
    return value == null || value.isBlank() ? "default" : value;
  }
}

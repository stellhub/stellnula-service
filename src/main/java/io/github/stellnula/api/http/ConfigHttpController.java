package io.github.stellnula.api.http;

import io.github.stellnula.application.ConfigDataPlaneService;
import io.github.stellnula.application.ProtocolCompatibilityService;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ClientSubscriptionFilter;
import io.github.stellnula.domain.ConfigChange;
import io.github.stellnula.domain.ConfigDelta;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigSnapshot;
import io.github.stellnula.domain.DataPlaneException;
import io.github.stellnula.domain.ProtocolOptions;
import io.github.stellnula.domain.ProtocolResponseMeta;
import io.github.stellnula.domain.ServerEndpoint;
import io.github.stellnula.repository.ClientSnapshotState;
import io.github.stellnula.repository.ClientSubscriptionState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/client")
public class ConfigHttpController {

  private final ConfigDataPlaneService dataPlaneService;
  private final ProtocolCompatibilityService protocolCompatibilityService;
  private final DataPlaneProperties properties;

  /** 客户端首次启动 HTTP bootstrap。 */
  @PostMapping("/bootstrap")
  public BootstrapResponse bootstrap(@Valid @RequestBody BootstrapRequest request) {
    ProtocolOptions options = protocolCompatibilityService.normalize(request.toProtocolOptions());
    ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
    ConfigDataPlaneService.BootstrapResult result =
        dataPlaneService.bootstrap(request.toClientBootstrapState());
    return toBootstrapResponse(result, protocol, options);
  }

  /** 客户端全量配置拉取。 */
  @GetMapping("/configs/full")
  public SnapshotResponse full(
      @RequestParam @NotBlank String appId,
      @RequestParam(defaultValue = "http-client") String clientId,
      @RequestParam @NotBlank String env,
      @RequestParam(defaultValue = "default") String region,
      @RequestParam(defaultValue = "default") String zone,
      @RequestParam(defaultValue = "default") String cluster,
      @RequestParam(name = "namespace", defaultValue = "default") String namespaceCode,
      @RequestParam(name = "group", defaultValue = "default") String groupCode,
      @RequestParam(defaultValue = "") String labels,
      @RequestParam(defaultValue = "") String subscriptions,
      @RequestParam(defaultValue = "") String apiVersion,
      @RequestParam(defaultValue = "") String sdkVersion,
      @RequestParam(defaultValue = "") String acceptedCompressions,
      @RequestParam(defaultValue = "0") int pageSize,
      @RequestParam(defaultValue = "") String pageToken,
      @RequestParam(defaultValue = "0") int maxPayloadBytes,
      @RequestParam(defaultValue = "false") boolean acceptLargeFileReference,
      @RequestHeader(name = "X-API-Version", required = false) String headerApiVersion,
      @RequestHeader(name = "X-SDK-Version", required = false) String headerSdkVersion,
      @RequestHeader(name = "Accept-Encoding", required = false) String acceptEncoding,
      @RequestHeader(name = "X-Client-IP", required = false) String clientIp,
      @RequestHeader(name = "X-Labels", required = false) String headerLabels,
      @RequestHeader(name = "X-Subscriptions", required = false) String headerSubscriptions) {
    ProtocolOptions options =
        protocolCompatibilityService.normalize(
            new ProtocolOptions(
                defaultText(headerApiVersion, apiVersion),
                defaultText(headerSdkVersion, sdkVersion),
                parseList(defaultText(acceptEncoding, acceptedCompressions)),
                pageSize,
                pageToken,
                maxPayloadBytes,
                acceptLargeFileReference));
    ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
    ConfigSnapshot snapshot =
        dataPlaneService.fetchFull(
            new ClientContext(
                appId,
                clientId,
                env,
                region,
                zone,
                cluster,
                namespaceCode,
                groupCode,
                clientIp,
                parseLabels(headerLabels == null || headerLabels.isBlank() ? labels : headerLabels),
                parseSubscriptionFilters(
                    headerSubscriptions == null || headerSubscriptions.isBlank()
                        ? subscriptions
                        : headerSubscriptions,
                    groupCode)),
            "HTTP");
    return toSnapshotResponse(snapshot, protocol, options);
  }

  /** 客户端增量配置拉取。 */
  @GetMapping("/configs/delta")
  public DeltaResponse delta(
      @RequestParam @NotBlank String appId,
      @RequestParam(defaultValue = "http-client") String clientId,
      @RequestParam @NotBlank String env,
      @RequestParam(defaultValue = "default") String region,
      @RequestParam(defaultValue = "default") String zone,
      @RequestParam(defaultValue = "default") String cluster,
      @RequestParam(name = "namespace", defaultValue = "default") String namespaceCode,
      @RequestParam(name = "group", defaultValue = "default") String groupCode,
      @RequestParam @PositiveOrZero long fromRevision,
      @RequestParam(defaultValue = "") String labels,
      @RequestParam(defaultValue = "") String subscriptions,
      @RequestParam(defaultValue = "") String apiVersion,
      @RequestParam(defaultValue = "") String sdkVersion,
      @RequestParam(defaultValue = "") String acceptedCompressions,
      @RequestParam(defaultValue = "0") int pageSize,
      @RequestParam(defaultValue = "") String pageToken,
      @RequestParam(defaultValue = "0") int maxPayloadBytes,
      @RequestParam(defaultValue = "false") boolean acceptLargeFileReference,
      @RequestHeader(name = "X-API-Version", required = false) String headerApiVersion,
      @RequestHeader(name = "X-SDK-Version", required = false) String headerSdkVersion,
      @RequestHeader(name = "Accept-Encoding", required = false) String acceptEncoding,
      @RequestHeader(name = "X-Client-IP", required = false) String clientIp,
      @RequestHeader(name = "X-Labels", required = false) String headerLabels,
      @RequestHeader(name = "X-Subscriptions", required = false) String headerSubscriptions) {
    ProtocolOptions options =
        protocolCompatibilityService.normalize(
            new ProtocolOptions(
                defaultText(headerApiVersion, apiVersion),
                defaultText(headerSdkVersion, sdkVersion),
                parseList(defaultText(acceptEncoding, acceptedCompressions)),
                pageSize,
                pageToken,
                maxPayloadBytes,
                acceptLargeFileReference));
    ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
    ConfigDelta delta =
        dataPlaneService.fetchDelta(
            new ClientContext(
                appId,
                clientId,
                env,
                region,
                zone,
                cluster,
                namespaceCode,
                groupCode,
                clientIp,
                parseLabels(headerLabels == null || headerLabels.isBlank() ? labels : headerLabels),
                parseSubscriptionFilters(
                    headerSubscriptions == null || headerSubscriptions.isBlank()
                        ? subscriptions
                        : headerSubscriptions,
                    groupCode)),
            fromRevision,
            "HTTP");
    return toDeltaResponse(delta, protocol, options);
  }

  /** 客户端按需读取大文件配置内容。 */
  @GetMapping("/configs/content")
  public ConfigContentResponse content(
      @RequestParam @NotBlank String appId,
      @RequestParam(defaultValue = "http-client") String clientId,
      @RequestParam @NotBlank String env,
      @RequestParam(defaultValue = "default") String region,
      @RequestParam(defaultValue = "default") String zone,
      @RequestParam(defaultValue = "default") String cluster,
      @RequestParam(name = "namespace", defaultValue = "default") String namespaceCode,
      @RequestParam(name = "group", defaultValue = "default") String groupCode,
      @RequestParam @NotBlank String configId,
      @RequestParam(defaultValue = "") String apiVersion,
      @RequestParam(defaultValue = "") String sdkVersion,
      @RequestParam(defaultValue = "") String acceptedCompressions,
      @RequestParam(defaultValue = "0") int maxPayloadBytes,
      @RequestHeader(name = "X-API-Version", required = false) String headerApiVersion,
      @RequestHeader(name = "X-SDK-Version", required = false) String headerSdkVersion,
      @RequestHeader(name = "Accept-Encoding", required = false) String acceptEncoding,
      @RequestHeader(name = "X-Client-IP", required = false) String clientIp) {
    ProtocolOptions options =
        protocolCompatibilityService.normalize(
            new ProtocolOptions(
                defaultText(headerApiVersion, apiVersion),
                defaultText(headerSdkVersion, sdkVersion),
                parseList(defaultText(acceptEncoding, acceptedCompressions)),
                1,
                "",
                maxPayloadBytes,
                false));
    ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
    ConfigSnapshot snapshot =
        dataPlaneService.fetchFull(
            new ClientContext(
                appId,
                clientId,
                env,
                region,
                zone,
                cluster,
                namespaceCode,
                groupCode,
                clientIp,
                Map.of(),
                List.of(new ClientSubscriptionFilter(groupCode, "ALL", configId))),
            "HTTP");
    ConfigEntry entry =
        snapshot.entries().stream()
            .filter(candidate -> candidate.configId().equals(configId))
            .findFirst()
            .orElseThrow(
                () ->
                    DataPlaneException.badRequest(
                        "config is not visible for current client context: " + configId,
                        protocolCompatibilityService.retryBackoff()));
    ConfigEntryResponse encoded = toEntryResponse(entry, options);
    return new ConfigContentResponse(protocol, encoded);
  }

  /** 客户端状态心跳。 */
  @PostMapping("/heartbeat")
  public ClientStateResponse heartbeat(@Valid @RequestBody ClientStateRequest request) {
    ProtocolOptions options = protocolCompatibilityService.normalize(request.toProtocolOptions());
    ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
    ConfigDataPlaneService.ClientStateResult result =
        dataPlaneService.reportClientState(
            request.toClientSnapshotState(),
            request.toClientContext(),
            request.sdkVersion(),
            request.hostName(),
            request.toClientSubscriptionStates());
    return new ClientStateResponse(protocol, result.accepted(), result.serverRevision());
  }

  public record BootstrapRequest(
      @NotBlank String appId,
      @NotBlank String clientId,
      String sdkVersion,
      @NotBlank String env,
      String region,
      String zone,
      String cluster,
      String namespace,
      String group,
      String clientIp,
      Map<String, String> labels,
      long currentRevision,
      List<SubscriptionRequest> subscriptions,
      List<String> supportedTransports,
      String apiVersion,
      String acceptedCompressions,
      Integer pageSize,
      String pageToken,
      Integer maxPayloadBytes,
      Boolean acceptLargeFileReference) {

    ConfigDataPlaneService.ClientBootstrapState toClientBootstrapState() {
      return new ConfigDataPlaneService.ClientBootstrapState(
          new ClientContext(
              appId,
              clientId,
              env,
              region,
              zone,
              cluster,
              namespace,
              group,
              clientIp,
              labels,
              toClientSubscriptionFilters(subscriptions, group)),
          sdkVersion,
          supportedTransports);
    }

    ProtocolOptions toProtocolOptions() {
      return new ProtocolOptions(
          apiVersion,
          sdkVersion,
          parseList(acceptedCompressions),
          pageSize == null ? 0 : pageSize,
          pageToken,
          maxPayloadBytes == null ? 0 : maxPayloadBytes,
          Boolean.TRUE.equals(acceptLargeFileReference));
    }
  }

  public record BootstrapResponse(
      ProtocolResponseMeta protocol,
      OffsetDateTime serverTime,
      long revision,
      String snapshotChecksum,
      List<ConfigEntryResponse> configs,
      GrpcOptionsResponse grpc,
      List<ServerEndpointResponse> servers,
      LoadBalancingResponse loadBalancing) {}

  public record SnapshotResponse(
      ProtocolResponseMeta protocol,
      long revision,
      String checksum,
      List<ConfigEntryResponse> entries) {}

  public record DeltaResponse(
      ProtocolResponseMeta protocol,
      long fromRevision,
      long toRevision,
      String checksum,
      List<ConfigChangeResponse> changes) {}

  public record ConfigChangeResponse(String type, ConfigEntryResponse entry) {}

  public record ConfigEntryResponse(
      String configId,
      String configKey,
      String contentType,
      String value,
      long version,
      long revision,
      boolean encrypted,
      boolean deleted,
      String matchedType,
      Long matchedGrayId,
      String matchedGrayName,
      Long grayVersion,
      String valueEncoding,
      String deliveryMode,
      int valueSizeBytes,
      String valueRef,
      ScopeResponse scope) {}

  public record ScopeResponse(String env, String region, String zone, String cluster) {}

  public record GrpcOptionsResponse(
      String preferredTransport, int watchTimeoutMillis, long heartbeatMillis) {}

  public record ServerEndpointResponse(
      String serverId,
      String httpAddress,
      String grpcAddress,
      int weight,
      String region,
      String zone,
      boolean healthy,
      String status,
      int activeWatchCount,
      double loadScore,
      int failureCount) {

    static ServerEndpointResponse from(ServerEndpoint endpoint) {
      return new ServerEndpointResponse(
          endpoint.serverId(),
          endpoint.httpAddress(),
          endpoint.grpcAddress(),
          endpoint.weight(),
          endpoint.region(),
          endpoint.zone(),
          endpoint.healthy(),
          endpoint.status(),
          endpoint.activeWatchCount(),
          endpoint.loadScore(),
          endpoint.failureCount());
    }
  }

  public record LoadBalancingResponse(
      String strategy, String hashKey, String failover, long ttlSeconds, List<String> reselectOn) {}

  public record ClientStateRequest(
      @NotBlank String appId,
      @NotBlank String clientId,
      @NotBlank String env,
      String region,
      String zone,
      String cluster,
      String namespace,
      String group,
      String clientIp,
      String hostName,
      String sdkVersion,
      Map<String, String> labels,
      long localRevision,
      String localChecksum,
      boolean localFileLoaded,
      OffsetDateTime lastSuccessSyncTime,
      List<SubscriptionRequest> subscriptions,
      String apiVersion) {

    ClientContext toClientContext() {
      return new ClientContext(
          appId,
          clientId,
          env,
          region,
          zone,
          cluster,
          namespace,
          group,
          clientIp,
          labels,
          toClientSubscriptionFilters(subscriptions, group));
    }

    ClientSnapshotState toClientSnapshotState() {
      ClientContext context = toClientContext().normalize();
      return new ClientSnapshotState(
          context.appId(),
          context.clientId(),
          context.env(),
          context.region(),
          context.zone(),
          context.cluster(),
          context.namespaceCode(),
          context.groupCode(),
          localRevision,
          localChecksum == null ? "" : localChecksum,
          localFileLoaded,
          lastSuccessSyncTime);
    }

    List<ClientSubscriptionState> toClientSubscriptionStates() {
      ClientContext context = toClientContext().normalize();
      if (subscriptions == null || subscriptions.isEmpty()) {
        return List.of();
      }
      return subscriptions.stream()
          .map(subscription -> subscription.toState(context, localRevision, localChecksum))
          .toList();
    }

    ProtocolOptions toProtocolOptions() {
      return new ProtocolOptions(apiVersion, sdkVersion, List.of(), 0, "", 0, false);
    }
  }

  public record SubscriptionRequest(
      String group,
      String subscriptionType,
      String subscriptionKey,
      Long currentRevision,
      String currentChecksum,
      String transport,
      String status) {

    ClientSubscriptionState toState(
        ClientContext context, long defaultRevision, String defaultChecksum) {
      return new ClientSubscriptionState(
          context.appId(),
          context.clientId(),
          context.env(),
          context.region(),
          context.zone(),
          context.cluster(),
          context.namespaceCode(),
          defaultText(group, context.groupCode()),
          normalizeSubscriptionType(subscriptionType),
          defaultText(subscriptionKey, "*"),
          currentRevision == null ? defaultRevision : currentRevision,
          defaultText(currentChecksum, defaultText(defaultChecksum, "")),
          normalizeTransport(transport),
          defaultText(status, "ACTIVE"));
    }

    ClientSubscriptionFilter toFilter(String defaultGroup) {
      return new ClientSubscriptionFilter(
          defaultText(group, defaultGroup),
          normalizeSubscriptionType(subscriptionType),
          defaultText(subscriptionKey, "*"));
    }
  }

  public record ConfigContentResponse(ProtocolResponseMeta protocol, ConfigEntryResponse content) {}

  public record ClientStateResponse(
      ProtocolResponseMeta protocol, boolean accepted, long serverRevision) {}

  private BootstrapResponse toBootstrapResponse(
      ConfigDataPlaneService.BootstrapResult result,
      ProtocolResponseMeta protocol,
      ProtocolOptions options) {
    ConfigSnapshot snapshot = result.snapshot();
    Page<ConfigEntry> page = page(snapshot.entries(), options);
    return new BootstrapResponse(
        protocol.withPage(options.pageSize(), page.nextPageToken(), page.hasMore()),
        result.serverTime(),
        snapshot.revision(),
        snapshot.checksum(),
        page.items().stream().map(entry -> toEntryResponse(entry, options)).toList(),
        new GrpcOptionsResponse(
            result.preferredTransport(), result.watchTimeoutMillis(), result.heartbeatMillis()),
        result.servers().stream().map(ServerEndpointResponse::from).toList(),
        new LoadBalancingResponse(
            result.loadBalancing().strategy(),
            result.loadBalancing().hashKey(),
            result.loadBalancing().failover(),
            result.loadBalancing().ttlSeconds(),
            List.of("ADDRESS_TTL_EXPIRED", "WATCH_ERROR", "SERVER_DRAINING", "UNHEALTHY")));
  }

  private SnapshotResponse toSnapshotResponse(
      ConfigSnapshot snapshot, ProtocolResponseMeta protocol, ProtocolOptions options) {
    Page<ConfigEntry> page = page(snapshot.entries(), options);
    return new SnapshotResponse(
        protocol.withPage(options.pageSize(), page.nextPageToken(), page.hasMore()),
        snapshot.revision(),
        snapshot.checksum(),
        page.items().stream().map(entry -> toEntryResponse(entry, options)).toList());
  }

  private DeltaResponse toDeltaResponse(
      ConfigDelta delta, ProtocolResponseMeta protocol, ProtocolOptions options) {
    Page<ConfigChange> page = page(delta.changes(), options);
    ProtocolResponseMeta responseMeta =
        protocol.withPage(options.pageSize(), page.nextPageToken(), page.hasMore());
    if (delta.fullSyncRequired()) {
      responseMeta = responseMeta.withFullSyncRequired(delta.fullSyncReason());
    }
    return new DeltaResponse(
        responseMeta,
        delta.fromRevision(),
        delta.toRevision(),
        delta.checksum(),
        page.items().stream()
            .map(
                change ->
                    new ConfigChangeResponse(
                        change.type().name(), toEntryResponse(change.entry(), options)))
            .toList());
  }

  private ConfigEntryResponse toEntryResponse(ConfigEntry entry, ProtocolOptions options) {
    byte[] rawBytes = entry.value().getBytes(StandardCharsets.UTF_8);
    EncodedValue encoded = encodeValue(entry, options, rawBytes);
    return new ConfigEntryResponse(
        entry.configId(),
        entry.configKey(),
        entry.contentType(),
        encoded.value(),
        entry.version(),
        entry.revision(),
        entry.encrypted(),
        entry.deleted(),
        entry.matchedType(),
        entry.matchedGrayId(),
        entry.matchedGrayName(),
        entry.grayVersion(),
        encoded.encoding(),
        encoded.deliveryMode(),
        rawBytes.length,
        encoded.valueRef(),
        new ScopeResponse(
            entry.scope().env(),
            entry.scope().region(),
            entry.scope().zone(),
            entry.scope().cluster()));
  }

  private EncodedValue encodeValue(ConfigEntry entry, ProtocolOptions options, byte[] rawBytes) {
    if (shouldUseLargeFileReference(entry, options, rawBytes.length)) {
      return new EncodedValue("", "identity", "REFERENCE", valueRef(entry));
    }
    if (rawBytes.length > options.maxPayloadBytes()) {
      throw DataPlaneException.payloadTooLarge(
          "config payload exceeds maxPayloadBytes, configId=" + entry.configId(),
          protocolCompatibilityService.retryBackoff());
    }
    if (rawBytes.length >= properties.compressionThresholdBytes()
        && options.acceptsCompression("gzip")) {
      return new EncodedValue(gzipBase64(rawBytes), "gzip+base64", "INLINE_COMPRESSED", "");
    }
    return new EncodedValue(entry.value(), "identity", "INLINE", "");
  }

  private boolean shouldUseLargeFileReference(
      ConfigEntry entry, ProtocolOptions options, int valueSizeBytes) {
    return options.acceptLargeFileReference()
        && "FILE".equalsIgnoreCase(entry.contentType())
        && valueSizeBytes >= properties.largeFileReferenceThresholdBytes();
  }

  private String valueRef(ConfigEntry entry) {
    return "stellnula://configs/" + entry.configId() + "/revisions/" + entry.revision();
  }

  private String gzipBase64(byte[] rawBytes) {
    try {
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
      try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
        gzipStream.write(rawBytes);
      }
      return Base64.getEncoder().encodeToString(byteStream.toByteArray());
    } catch (IOException ex) {
      throw new IllegalStateException("failed to gzip config payload", ex);
    }
  }

  private static <T> Page<T> page(List<T> items, ProtocolOptions options) {
    int offset = parsePageToken(options.pageToken());
    if (offset >= items.size()) {
      return new Page<>(List.of(), "", false);
    }
    int end = Math.min(items.size(), offset + options.pageSize());
    boolean hasMore = end < items.size();
    return new Page<>(items.subList(offset, end), hasMore ? Integer.toString(end) : "", hasMore);
  }

  private static int parsePageToken(String pageToken) {
    if (pageToken == null || pageToken.isBlank()) {
      return 0;
    }
    try {
      int offset = Integer.parseInt(pageToken);
      if (offset < 0) {
        throw new NumberFormatException("negative page token");
      }
      return offset;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("pageToken is invalid: " + pageToken);
    }
  }

  private record Page<T>(List<T> items, String nextPageToken, boolean hasMore) {}

  private record EncodedValue(
      String value, String encoding, String deliveryMode, String valueRef) {}

  private static Map<String, String> parseLabels(String value) {
    if (value == null || value.isBlank()) {
      return Map.of();
    }
    return List.of(value.split(",")).stream()
        .map(String::trim)
        .filter(part -> !part.isBlank() && part.contains("="))
        .map(part -> part.split("=", 2))
        .collect(Collectors.toUnmodifiableMap(part -> part[0].trim(), part -> part[1].trim()));
  }

  private static String normalizeSubscriptionType(String value) {
    String resolved = defaultText(value, "ALL").toUpperCase();
    return switch (resolved) {
      case "CONFIG", "PUBLIC_CONFIG", "GOVERNANCE_RULE", "ALL" -> resolved;
      default -> throw new IllegalArgumentException("subscriptionType is not supported: " + value);
    };
  }

  private static String normalizeTransport(String value) {
    return "HTTP".equalsIgnoreCase(value) ? "HTTP" : "GRPC";
  }

  private static List<ClientSubscriptionFilter> parseSubscriptionFilters(
      String value, String defaultGroup) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return List.of(value.split(",")).stream()
        .map(String::trim)
        .filter(part -> !part.isBlank())
        .map(part -> parseSubscriptionFilter(part, defaultGroup))
        .toList();
  }

  private static ClientSubscriptionFilter parseSubscriptionFilter(
      String value, String defaultGroup) {
    String[] parts = value.split(":", 3);
    if (parts.length == 1) {
      return new ClientSubscriptionFilter(defaultText(defaultGroup, "default"), "ALL", parts[0]);
    }
    if (parts.length == 2) {
      return new ClientSubscriptionFilter(defaultText(defaultGroup, "default"), parts[0], parts[1]);
    }
    return new ClientSubscriptionFilter(parts[1], parts[0], parts[2]);
  }

  private static List<ClientSubscriptionFilter> toClientSubscriptionFilters(
      List<SubscriptionRequest> subscriptions, String defaultGroup) {
    if (subscriptions == null || subscriptions.isEmpty()) {
      return List.of();
    }
    return subscriptions.stream()
        .map(subscription -> subscription.toFilter(defaultText(defaultGroup, "default")))
        .toList();
  }

  private static List<String> parseList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return List.of(value.split(",")).stream()
        .map(String::trim)
        .filter(part -> !part.isBlank())
        .toList();
  }

  private static String defaultText(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}

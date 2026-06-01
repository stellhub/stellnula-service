package io.github.stellnula.api.grpc;

import io.github.stellflux.grpc.server.annotation.RpcService;
import io.github.stellnula.application.ConfigDataPlaneService;
import io.github.stellnula.application.ProtocolCompatibilityService;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ClientSubscriptionFilter;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.DataPlaneException;
import io.github.stellnula.domain.ProtocolOptions;
import io.github.stellnula.domain.ProtocolResponseMeta;
import io.github.stellnula.domain.WatchResult;
import io.github.stellnula.protocol.grpc.v1.ChangeType;
import io.github.stellnula.protocol.grpc.v1.ClientStateRequest;
import io.github.stellnula.protocol.grpc.v1.ClientStateResponse;
import io.github.stellnula.protocol.grpc.v1.ConfigChange;
import io.github.stellnula.protocol.grpc.v1.ConfigDelta;
import io.github.stellnula.protocol.grpc.v1.ConfigSnapshot;
import io.github.stellnula.protocol.grpc.v1.FetchDeltaRequest;
import io.github.stellnula.protocol.grpc.v1.FetchFullRequest;
import io.github.stellnula.protocol.grpc.v1.StellnulaConfigServiceGrpc;
import io.github.stellnula.protocol.grpc.v1.WatchRequest;
import io.github.stellnula.protocol.grpc.v1.WatchResponse;
import io.github.stellnula.protocol.grpc.v1.WatchStatus;
import io.github.stellnula.repository.ClientSnapshotState;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RpcService(serviceId = "stellnula.config.grpc")
public class GrpcConfigDataPlaneService
    extends StellnulaConfigServiceGrpc.StellnulaConfigServiceImplBase {

  private final ConfigDataPlaneService dataPlaneService;
  private final ProtocolCompatibilityService protocolCompatibilityService;
  private final DataPlaneProperties properties;

  /** gRPC 长轮询配置变更。 */
  @Override
  public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
    try {
      ProtocolOptions options = protocolCompatibilityService.normalize(toProtocolOptions(request));
      ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
      WatchResult result =
          dataPlaneService.watch(
              toClientContext(request.getContext()),
              request.getCurrentRevision(),
              request.getTimeoutMillis());
      responseObserver.onNext(toWatchResponse(result, protocol, options));
      responseObserver.onCompleted();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      responseObserver.onError(ex);
    } catch (RuntimeException ex) {
      responseObserver.onError(toGrpcError(ex));
    }
  }

  /** gRPC 全量配置拉取。 */
  @Override
  public void fetchFull(FetchFullRequest request, StreamObserver<ConfigSnapshot> responseObserver) {
    try {
      ProtocolOptions options = protocolCompatibilityService.normalize(toProtocolOptions(request));
      ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
      responseObserver.onNext(
          toGrpcSnapshot(
              dataPlaneService.fetchFull(toClientContext(request.getContext())),
              protocol,
              options));
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onError(toGrpcError(ex));
    }
  }

  /** gRPC 增量配置拉取。 */
  @Override
  public void fetchDelta(FetchDeltaRequest request, StreamObserver<ConfigDelta> responseObserver) {
    try {
      ProtocolOptions options = protocolCompatibilityService.normalize(toProtocolOptions(request));
      ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
      responseObserver.onNext(
          toGrpcDelta(
              dataPlaneService.fetchDelta(
                  toClientContext(request.getContext()), request.getFromRevision()),
              protocol,
              options));
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onError(toGrpcError(ex));
    }
  }

  /** gRPC 客户端状态上报。 */
  @Override
  public void reportClientState(
      ClientStateRequest request, StreamObserver<ClientStateResponse> responseObserver) {
    try {
      ProtocolOptions options = protocolCompatibilityService.normalize(toProtocolOptions(request));
      ProtocolResponseMeta protocol = protocolCompatibilityService.negotiate(options);
      ClientContext context = toClientContext(request.getContext()).normalize();
      ConfigDataPlaneService.ClientStateResult result =
          dataPlaneService.reportClientState(
              new ClientSnapshotState(
                  context.appId(),
                  context.clientId(),
                  context.env(),
                  context.region(),
                  context.zone(),
                  context.cluster(),
                  context.namespaceCode(),
                  context.groupCode(),
                  request.getLocalRevision(),
                  request.getLocalChecksum(),
                  request.getLocalFileLoaded(),
                  parseTime(request.getLastSuccessSyncTime())),
              context,
              request.getSdkVersion(),
              request.getHostName());
      responseObserver.onNext(
          ClientStateResponse.newBuilder()
              .setAccepted(result.accepted())
              .setServerRevision(result.serverRevision())
              .setMeta(toGrpcMeta(protocol))
              .build());
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onError(toGrpcError(ex));
    }
  }

  private ClientContext toClientContext(
      io.github.stellnula.protocol.grpc.v1.ClientContext context) {
    return new ClientContext(
        context.getAppId(),
        context.getClientId(),
        context.getEnv(),
        context.getRegion(),
        context.getZone(),
        context.getCluster(),
        context.getNamespace(),
        context.getGroup(),
        context.getClientIp(),
        context.getLabelsMap(),
        context.getSubscriptionsList().stream()
            .map(
                subscription ->
                    new ClientSubscriptionFilter(
                        subscription.getGroup(),
                        subscription.getSubscriptionType(),
                        subscription.getSubscriptionKey()))
            .toList());
  }

  private WatchResponse toWatchResponse(
      WatchResult result, ProtocolResponseMeta protocol, ProtocolOptions options) {
    Page<io.github.stellnula.domain.ConfigChange> page = page(result.changes(), options);
    ProtocolResponseMeta responseMeta =
        protocol.withPage(options.pageSize(), page.nextPageToken(), page.hasMore());
    if (result.fullSyncRequired()) {
      responseMeta = responseMeta.withFullSyncRequired(result.fullSyncReason());
    }
    WatchResponse.Builder builder =
        WatchResponse.newBuilder()
            .setStatus(toGrpcStatus(result.status()))
            .setLatestRevision(result.latestRevision())
            .setLatestChecksum(result.latestChecksum())
            .setFullSyncRequired(result.fullSyncRequired())
            .setFullSyncReason(result.fullSyncReason())
            .setMeta(toGrpcMeta(responseMeta));
    page.items().forEach(change -> builder.addChanges(toGrpcChange(change, options)));
    return builder.build();
  }

  private WatchStatus toGrpcStatus(io.github.stellnula.domain.WatchStatus status) {
    return switch (status) {
      case CHANGED -> WatchStatus.CHANGED;
      case NO_CHANGE -> WatchStatus.NO_CHANGE;
      case CLIENT_TOO_OLD -> WatchStatus.CLIENT_TOO_OLD;
      case UNAUTHORIZED -> WatchStatus.UNAUTHORIZED;
    };
  }

  private ConfigSnapshot toGrpcSnapshot(
      io.github.stellnula.domain.ConfigSnapshot snapshot,
      ProtocolResponseMeta protocol,
      ProtocolOptions options) {
    Page<ConfigEntry> page = page(snapshot.entries(), options);
    ConfigSnapshot.Builder builder =
        ConfigSnapshot.newBuilder()
            .setRevision(snapshot.revision())
            .setChecksum(snapshot.checksum())
            .setMeta(
                toGrpcMeta(
                    protocol.withPage(options.pageSize(), page.nextPageToken(), page.hasMore())));
    page.items().forEach(entry -> builder.addEntries(toGrpcEntry(entry, options)));
    return builder.build();
  }

  private ConfigDelta toGrpcDelta(
      io.github.stellnula.domain.ConfigDelta delta,
      ProtocolResponseMeta protocol,
      ProtocolOptions options) {
    Page<io.github.stellnula.domain.ConfigChange> page = page(delta.changes(), options);
    ProtocolResponseMeta responseMeta =
        protocol.withPage(options.pageSize(), page.nextPageToken(), page.hasMore());
    if (delta.fullSyncRequired()) {
      responseMeta = responseMeta.withFullSyncRequired(delta.fullSyncReason());
    }
    ConfigDelta.Builder builder =
        ConfigDelta.newBuilder()
            .setFromRevision(delta.fromRevision())
            .setToRevision(delta.toRevision())
            .setChecksum(delta.checksum())
            .setFullSyncRequired(delta.fullSyncRequired())
            .setFullSyncReason(delta.fullSyncReason())
            .setMeta(toGrpcMeta(responseMeta));
    page.items().forEach(change -> builder.addChanges(toGrpcChange(change, options)));
    return builder.build();
  }

  private ConfigChange toGrpcChange(
      io.github.stellnula.domain.ConfigChange change, ProtocolOptions options) {
    return ConfigChange.newBuilder()
        .setType(toGrpcChangeType(change.type()))
        .setEntry(toGrpcEntry(change.entry(), options))
        .build();
  }

  private ChangeType toGrpcChangeType(io.github.stellnula.domain.ChangeType type) {
    return switch (type) {
      case UPSERT -> ChangeType.UPSERT;
      case DELETE -> ChangeType.DELETE;
      case GRAY_CHANGED -> ChangeType.GRAY_CHANGED;
    };
  }

  private io.github.stellnula.protocol.grpc.v1.ConfigEntry toGrpcEntry(
      ConfigEntry entry, ProtocolOptions options) {
    EncodedValue encoded = encodeValue(entry, options);
    int valueSizeBytes = entry.value().getBytes(StandardCharsets.UTF_8).length;
    return io.github.stellnula.protocol.grpc.v1.ConfigEntry.newBuilder()
        .setConfigId(entry.configId())
        .setConfigKey(entry.configKey())
        .setContentType(entry.contentType())
        .setValue(encoded.value())
        .setVersion(entry.version())
        .setRevision(entry.revision())
        .setEncrypted(entry.encrypted())
        .setDeleted(entry.deleted())
        .setMatchedType(entry.matchedType())
        .setMatchedGrayId(entry.matchedGrayId() == null ? 0 : entry.matchedGrayId())
        .setMatchedGrayName(entry.matchedGrayName() == null ? "" : entry.matchedGrayName())
        .setGrayVersion(entry.grayVersion() == null ? 0 : entry.grayVersion())
        .setValueEncoding(encoded.encoding())
        .setDeliveryMode(encoded.deliveryMode())
        .setValueSizeBytes(valueSizeBytes)
        .setValueRef(encoded.valueRef())
        .build();
  }

  private OffsetDateTime parseTime(String value) {
    return value == null || value.isBlank() ? null : OffsetDateTime.parse(value);
  }

  private ProtocolOptions toProtocolOptions(WatchRequest request) {
    return toProtocolOptions(request.hasOptions() ? request.getOptions() : null);
  }

  private ProtocolOptions toProtocolOptions(FetchFullRequest request) {
    return toProtocolOptions(request.hasOptions() ? request.getOptions() : null);
  }

  private ProtocolOptions toProtocolOptions(FetchDeltaRequest request) {
    return toProtocolOptions(request.hasOptions() ? request.getOptions() : null);
  }

  private ProtocolOptions toProtocolOptions(ClientStateRequest request) {
    if (request.hasOptions()) {
      return toProtocolOptions(request.getOptions());
    }
    return new ProtocolOptions("", request.getSdkVersion(), List.of(), 0, "", 0, false);
  }

  private ProtocolOptions toProtocolOptions(
      io.github.stellnula.protocol.grpc.v1.ProtocolOptions options) {
    if (options == null) {
      return ProtocolOptions.defaults();
    }
    return new ProtocolOptions(
        options.getApiVersion(),
        options.getSdkVersion(),
        options.getAcceptedCompressionsList(),
        options.getPageSize(),
        options.getPageToken(),
        options.getMaxPayloadBytes(),
        options.getAcceptLargeFileReference());
  }

  private io.github.stellnula.protocol.grpc.v1.ProtocolMeta toGrpcMeta(ProtocolResponseMeta meta) {
    return io.github.stellnula.protocol.grpc.v1.ProtocolMeta.newBuilder()
        .setApiVersion(meta.apiVersion())
        .setMinSupportedApiVersion(meta.minSupportedApiVersion())
        .setServerVersion(meta.serverVersion())
        .setSdkCompatibility(meta.sdkCompatibility())
        .setCompression(meta.compression())
        .setPageSize(meta.pageSize())
        .setNextPageToken(meta.nextPageToken())
        .setHasMore(meta.hasMore())
        .setRetryAfterMillis(meta.retryAfterMillis())
        .setRetryBackoff(toGrpcBackoff(meta.retryBackoff()))
        .setFullSyncRequired(meta.fullSyncRequired())
        .setFullSyncReason(meta.fullSyncReason())
        .build();
  }

  private io.github.stellnula.protocol.grpc.v1.RetryBackoffHint toGrpcBackoff(
      io.github.stellnula.domain.RetryBackoffHint hint) {
    return io.github.stellnula.protocol.grpc.v1.RetryBackoffHint.newBuilder()
        .setInitialDelayMillis(hint.initialDelayMillis())
        .setMaxDelayMillis(hint.maxDelayMillis())
        .setMultiplier(hint.multiplier())
        .setJitterRatio(hint.jitterRatio())
        .build();
  }

  private EncodedValue encodeValue(ConfigEntry entry, ProtocolOptions options) {
    byte[] rawBytes = entry.value().getBytes(StandardCharsets.UTF_8);
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

  private RuntimeException toGrpcError(RuntimeException ex) {
    if (ex instanceof DataPlaneException dataPlaneException) {
      Metadata metadata = new Metadata();
      metadata.put(
          Metadata.Key.of("stellnula-error-code", Metadata.ASCII_STRING_MARSHALLER),
          dataPlaneException.errorCode().name());
      metadata.put(
          Metadata.Key.of("stellnula-retryable", Metadata.ASCII_STRING_MARSHALLER),
          Boolean.toString(dataPlaneException.retryable()));
      metadata.put(
          Metadata.Key.of("stellnula-retry-after-millis", Metadata.ASCII_STRING_MARSHALLER),
          Long.toString(dataPlaneException.retryAfterMillis()));
      metadata.put(
          Metadata.Key.of("stellnula-full-sync-required", Metadata.ASCII_STRING_MARSHALLER),
          Boolean.toString(dataPlaneException.fullSyncRequired()));
      metadata.put(
          Metadata.Key.of("stellnula-full-sync-reason", Metadata.ASCII_STRING_MARSHALLER),
          dataPlaneException.fullSyncReason());
      return grpcStatus(dataPlaneException)
          .withDescription(ex.getMessage())
          .asRuntimeException(metadata);
    }
    return Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException();
  }

  private Status grpcStatus(DataPlaneException ex) {
    return switch (ex.errorCode()) {
      case UNSUPPORTED_API_VERSION, SDK_UPGRADE_REQUIRED, BAD_REQUEST -> Status.INVALID_ARGUMENT;
      case TOO_MANY_WATCHES -> Status.RESOURCE_EXHAUSTED;
      case PAYLOAD_TOO_LARGE -> Status.OUT_OF_RANGE;
      case CONFIG_NOT_FOUND -> Status.NOT_FOUND;
      case FULL_SYNC_REQUIRED -> Status.FAILED_PRECONDITION;
      case INTERNAL_ERROR -> Status.INTERNAL;
    };
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
    int offset = Integer.parseInt(pageToken);
    if (offset < 0) {
      throw new IllegalArgumentException("pageToken is invalid: " + pageToken);
    }
    return offset;
  }

  private record Page<T>(List<T> items, String nextPageToken, boolean hasMore) {}

  private record EncodedValue(
      String value, String encoding, String deliveryMode, String valueRef) {}
}

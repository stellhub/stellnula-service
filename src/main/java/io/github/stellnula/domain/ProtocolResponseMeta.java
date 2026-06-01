package io.github.stellnula.domain;

public record ProtocolResponseMeta(
    String apiVersion,
    String minSupportedApiVersion,
    String serverVersion,
    String sdkCompatibility,
    String compression,
    int pageSize,
    String nextPageToken,
    boolean hasMore,
    long retryAfterMillis,
    RetryBackoffHint retryBackoff,
    boolean fullSyncRequired,
    String fullSyncReason) {

  public ProtocolResponseMeta withPage(int resolvedPageSize, String nextToken, boolean more) {
    return new ProtocolResponseMeta(
        apiVersion,
        minSupportedApiVersion,
        serverVersion,
        sdkCompatibility,
        compression,
        resolvedPageSize,
        nextToken,
        more,
        retryAfterMillis,
        retryBackoff,
        fullSyncRequired,
        fullSyncReason);
  }

  public ProtocolResponseMeta withFullSyncRequired(String reason) {
    return new ProtocolResponseMeta(
        apiVersion,
        minSupportedApiVersion,
        serverVersion,
        sdkCompatibility,
        compression,
        pageSize,
        nextPageToken,
        hasMore,
        retryAfterMillis,
        retryBackoff,
        true,
        reason == null ? "" : reason);
  }
}

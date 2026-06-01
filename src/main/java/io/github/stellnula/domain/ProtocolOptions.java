package io.github.stellnula.domain;

import java.util.List;

public record ProtocolOptions(
    String apiVersion,
    String sdkVersion,
    List<String> acceptedCompressions,
    int pageSize,
    String pageToken,
    int maxPayloadBytes,
    boolean acceptLargeFileReference) {

  public ProtocolOptions {
    acceptedCompressions =
        acceptedCompressions == null
            ? List.of()
            : acceptedCompressions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .distinct()
                .toList();
    pageToken = pageToken == null ? "" : pageToken;
  }

  public static ProtocolOptions defaults() {
    return new ProtocolOptions("v1", "", List.of(), 0, "", 0, false);
  }

  public boolean acceptsCompression(String compression) {
    return acceptedCompressions.stream().anyMatch(value -> value.equalsIgnoreCase(compression));
  }
}

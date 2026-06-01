package io.github.stellnula.application;

import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.DataPlaneErrorCode;
import io.github.stellnula.domain.DataPlaneException;
import io.github.stellnula.domain.ProtocolOptions;
import io.github.stellnula.domain.ProtocolResponseMeta;
import io.github.stellnula.domain.RetryBackoffHint;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProtocolCompatibilityService {

  private static final String SDK_COMPATIBLE = "COMPATIBLE";
  private static final String SDK_UNKNOWN = "UNKNOWN";
  private static final String SDK_UPGRADE_RECOMMENDED = "UPGRADE_RECOMMENDED";

  private final DataPlaneProperties properties;

  /** 协商客户端协议版本、SDK 版本和分页大小。 */
  public ProtocolResponseMeta negotiate(ProtocolOptions options) {
    ProtocolOptions normalized = normalize(options);
    if (!isApiVersionSupported(normalized.apiVersion())) {
      throw new DataPlaneException(
          DataPlaneErrorCode.UNSUPPORTED_API_VERSION,
          "apiVersion is not supported: " + normalized.apiVersion(),
          HttpStatus.BAD_REQUEST,
          false,
          0,
          retryBackoff(),
          false,
          "");
    }
    return new ProtocolResponseMeta(
        normalized.apiVersion(),
        properties.minApiVersion(),
        properties.serverVersion(),
        sdkCompatibility(normalized.sdkVersion()),
        "identity",
        resolvePageSize(normalized.pageSize()),
        "",
        false,
        0,
        retryBackoff(),
        false,
        "");
  }

  /** 规范化客户端能力选项。 */
  public ProtocolOptions normalize(ProtocolOptions options) {
    ProtocolOptions resolved = options == null ? ProtocolOptions.defaults() : options;
    return new ProtocolOptions(
        defaultText(resolved.apiVersion(), properties.currentApiVersion()),
        defaultText(resolved.sdkVersion(), ""),
        resolved.acceptedCompressions(),
        resolvePageSize(resolved.pageSize()),
        defaultText(resolved.pageToken(), ""),
        resolved.maxPayloadBytes() <= 0
            ? properties.maxResponsePayloadBytes()
            : Math.min(resolved.maxPayloadBytes(), properties.maxResponsePayloadBytes()),
        resolved.acceptLargeFileReference());
  }

  /** 获取重试退避建议。 */
  public RetryBackoffHint retryBackoff() {
    return new RetryBackoffHint(
        properties.clientRetryInitialMillis(),
        properties.clientRetryMaxMillis(),
        properties.clientRetryMultiplier(),
        properties.clientRetryJitterRatio());
  }

  private int resolvePageSize(int requestedPageSize) {
    int pageSize = requestedPageSize <= 0 ? properties.defaultPageSize() : requestedPageSize;
    return Math.min(Math.max(1, pageSize), properties.maxPageSize());
  }

  private boolean isApiVersionSupported(String apiVersion) {
    int requested = parseVersion(apiVersion);
    return requested >= parseVersion(properties.minApiVersion())
        && requested <= parseVersion(properties.currentApiVersion());
  }

  private String sdkCompatibility(String sdkVersion) {
    if (sdkVersion == null || sdkVersion.isBlank()) {
      return SDK_UNKNOWN;
    }
    return compareVersion(sdkVersion, properties.minSdkVersion()) >= 0
        ? SDK_COMPATIBLE
        : SDK_UPGRADE_RECOMMENDED;
  }

  private int parseVersion(String value) {
    String resolved = defaultText(value, "v1").trim().toLowerCase();
    if (resolved.startsWith("v")) {
      resolved = resolved.substring(1);
    }
    try {
      return Integer.parseInt(resolved);
    } catch (NumberFormatException ex) {
      throw DataPlaneException.badRequest("apiVersion format is invalid: " + value, retryBackoff());
    }
  }

  private int compareVersion(String left, String right) {
    List<Integer> leftParts = parseSemver(left);
    List<Integer> rightParts = parseSemver(right);
    for (int index = 0; index < Math.max(leftParts.size(), rightParts.size()); index++) {
      int leftValue = index < leftParts.size() ? leftParts.get(index) : 0;
      int rightValue = index < rightParts.size() ? rightParts.get(index) : 0;
      if (leftValue != rightValue) {
        return Integer.compare(leftValue, rightValue);
      }
    }
    return 0;
  }

  private List<Integer> parseSemver(String value) {
    return List.of(defaultText(value, "0").split("\\.")).stream()
        .map(part -> part.replaceAll("[^0-9].*$", ""))
        .map(part -> part.isBlank() ? 0 : Integer.parseInt(part))
        .toList();
  }

  private String defaultText(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}

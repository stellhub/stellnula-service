package io.github.stellnula.domain;

import org.springframework.http.HttpStatus;

public class DataPlaneException extends RuntimeException {

  private final DataPlaneErrorCode errorCode;
  private final HttpStatus httpStatus;
  private final boolean retryable;
  private final long retryAfterMillis;
  private final RetryBackoffHint retryBackoff;
  private final boolean fullSyncRequired;
  private final String fullSyncReason;

  public DataPlaneException(
      DataPlaneErrorCode errorCode,
      String message,
      HttpStatus httpStatus,
      boolean retryable,
      long retryAfterMillis,
      RetryBackoffHint retryBackoff,
      boolean fullSyncRequired,
      String fullSyncReason) {
    super(message);
    this.errorCode = errorCode;
    this.httpStatus = httpStatus;
    this.retryable = retryable;
    this.retryAfterMillis = retryAfterMillis;
    this.retryBackoff = retryBackoff;
    this.fullSyncRequired = fullSyncRequired;
    this.fullSyncReason = fullSyncReason == null ? "" : fullSyncReason;
  }

  public static DataPlaneException badRequest(String message, RetryBackoffHint retryBackoff) {
    return new DataPlaneException(
        DataPlaneErrorCode.BAD_REQUEST,
        message,
        HttpStatus.BAD_REQUEST,
        false,
        0,
        retryBackoff,
        false,
        "");
  }

  public static DataPlaneException payloadTooLarge(String message, RetryBackoffHint retryBackoff) {
    return new DataPlaneException(
        DataPlaneErrorCode.PAYLOAD_TOO_LARGE,
        message,
        HttpStatus.PAYLOAD_TOO_LARGE,
        false,
        0,
        retryBackoff,
        false,
        "");
  }

  public DataPlaneErrorCode errorCode() {
    return errorCode;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }

  public boolean retryable() {
    return retryable;
  }

  public long retryAfterMillis() {
    return retryAfterMillis;
  }

  public RetryBackoffHint retryBackoff() {
    return retryBackoff;
  }

  public boolean fullSyncRequired() {
    return fullSyncRequired;
  }

  public String fullSyncReason() {
    return fullSyncReason;
  }
}

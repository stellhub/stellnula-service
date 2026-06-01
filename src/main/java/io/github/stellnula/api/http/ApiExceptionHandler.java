package io.github.stellnula.api.http;

import io.github.stellnula.domain.DataPlaneErrorCode;
import io.github.stellnula.domain.DataPlaneException;
import io.github.stellnula.domain.RetryBackoffHint;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final RetryBackoffHint DEFAULT_BACKOFF =
      new RetryBackoffHint(500, 30000, 2.0, 0.2);

  /** 将数据面标准异常转换为统一错误模型。 */
  @ExceptionHandler(DataPlaneException.class)
  public ResponseEntity<ErrorResponse> handleDataPlaneException(DataPlaneException ex) {
    return ResponseEntity.status(ex.httpStatus())
        .body(
            new ErrorResponse(
                ex.errorCode().name(),
                ex.getMessage(),
                ex.retryable(),
                ex.retryAfterMillis(),
                ex.retryBackoff(),
                ex.fullSyncRequired(),
                ex.fullSyncReason(),
                OffsetDateTime.now()));
  }

  /** 将业务参数错误转换为 HTTP 400。 */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new ErrorResponse(
                DataPlaneErrorCode.BAD_REQUEST.name(),
                ex.getMessage(),
                false,
                0,
                DEFAULT_BACKOFF,
                false,
                "",
                OffsetDateTime.now()));
  }

  /** 将 Bean Validation 错误转换为 HTTP 400。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .orElse("request validation failed");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new ErrorResponse(
                DataPlaneErrorCode.BAD_REQUEST.name(),
                message,
                false,
                0,
                DEFAULT_BACKOFF,
                false,
                "",
                OffsetDateTime.now()));
  }

  public record ErrorResponse(
      String code,
      String message,
      boolean retryable,
      long retryAfterMillis,
      RetryBackoffHint retryBackoff,
      boolean fullSyncRequired,
      String fullSyncReason,
      OffsetDateTime serverTime) {}
}

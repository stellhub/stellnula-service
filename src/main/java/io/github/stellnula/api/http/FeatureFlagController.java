package io.github.stellnula.api.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.stellnula.application.ConfigMutationService;
import io.github.stellnula.application.FeatureFlagValidator;
import io.github.stellnula.application.FeatureFlagValidator.FeatureFlagDocument;
import io.github.stellnula.domain.ConfigMutationAction;
import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ControlPlaneAppConfigRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/control-plane/feature-flags")
public class FeatureFlagController {

  private static final String DEFAULT_APP_ID = "stellhub.core.middleware.stellcloud.admin";
  private static final String DEFAULT_OPERATOR = "config-control-plane";
  private static final String DEFAULT_SCOPE = "default";
  private static final String FEATURE_FLAG_OWNER_TYPE = "APPLICATION";
  private static final String FEATURE_FLAG_NAMESPACE = "app-config";
  private static final String FEATURE_FLAG_GROUP = "feature-flags";
  private static final String FEATURE_FLAG_CONFIG_ID_PREFIX = "feature.";

  private final ConfigMutationService mutationService;
  private final FeatureFlagValidator featureFlagValidator;
  private final ObjectMapper objectMapper;

  /** 查询 Feature Flag 列表。 */
  @GetMapping
  public FeatureFlagListResponse list(
      @RequestParam(name = "appId", required = false) String appId,
      @RequestParam(name = "environment", required = false) String environment,
      @RequestParam(name = "cluster", required = false) String cluster,
      @RequestParam(name = "group", required = false) String group,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader) {
    String groupFilter = hasText(group) ? resolveGroup(group) : "";
    List<FeatureFlagResponse> records =
        mutationService
            .findControlPlaneConfigs(
                FEATURE_FLAG_OWNER_TYPE,
                resolveAppId(appId, appIdHeader),
                FEATURE_FLAG_NAMESPACE,
                environment,
                cluster,
                groupFilter)
            .stream()
            .filter(record -> record.group().startsWith(FEATURE_FLAG_GROUP))
            .map(this::toResponse)
            .toList();
    return new FeatureFlagListResponse(records);
  }

  /** 查询 Feature Flag 详情。 */
  @GetMapping("/{flagKey}")
  public FeatureFlagResponse get(
      @PathVariable("flagKey") @NotBlank String flagKey,
      @RequestParam(name = "appId", required = false) String appId,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader) {
    return mutationService
        .findControlPlaneConfig(
            FEATURE_FLAG_OWNER_TYPE,
            resolveAppId(appId, appIdHeader),
            FEATURE_FLAG_NAMESPACE,
            toConfigId(flagKey))
        .filter(record -> record.group().startsWith(FEATURE_FLAG_GROUP))
        .map(this::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feature Flag 不存在"));
  }

  /** 创建 Feature Flag 草稿。 */
  @PostMapping
  public ResponseEntity<FeatureFlagResponse> create(
      @Valid @RequestBody FeatureFlagRequest request,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    String flagKey = resolveFlagKey(request.key(), null);
    String configId = toConfigId(flagKey);
    validateRequestId(request.id(), configId);
    mutationService.saveDraft(
        toCommand(ConfigMutationAction.UPSERT, flagKey, request, appIdHeader, operatorHeader));
    FeatureFlagResponse body =
        findSavedRecord(resolveAppId(request.appId(), appIdHeader), configId);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{flagKey}")
            .buildAndExpand(flagKey)
            .toUri();
    return ResponseEntity.created(location).body(body);
  }

  /** 保存 Feature Flag 草稿。 */
  @PutMapping("/{flagKey}")
  public FeatureFlagResponse saveDraft(
      @PathVariable("flagKey") @NotBlank String flagKey,
      @Valid @RequestBody FeatureFlagRequest request,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    String resolvedFlagKey = resolveFlagKey(request.key(), flagKey);
    validateRequestId(request.id(), toConfigId(resolvedFlagKey));
    mutationService.saveDraft(
        toCommand(
            ConfigMutationAction.UPSERT, resolvedFlagKey, request, appIdHeader, operatorHeader));
    return findSavedRecord(resolveAppId(request.appId(), appIdHeader), toConfigId(resolvedFlagKey));
  }

  /** 发布 Feature Flag。 */
  @PostMapping("/{flagKey}/publish")
  public FeatureFlagResponse publish(
      @PathVariable("flagKey") @NotBlank String flagKey,
      @Valid @RequestBody FeatureFlagRequest request,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    String resolvedFlagKey = resolveFlagKey(request.key(), flagKey);
    validateRequestId(request.id(), toConfigId(resolvedFlagKey));
    mutationService.upsert(
        toCommand(
            ConfigMutationAction.UPSERT, resolvedFlagKey, request, appIdHeader, operatorHeader));
    return findSavedRecord(resolveAppId(request.appId(), appIdHeader), toConfigId(resolvedFlagKey));
  }

  /** 删除 Feature Flag。 */
  @DeleteMapping("/{flagKey}")
  public ResponseEntity<Void> delete(
      @PathVariable("flagKey") @NotBlank String flagKey,
      @RequestParam(name = "appId", required = false) String appId,
      @RequestParam(name = "environment", required = false) String environment,
      @RequestParam(name = "cluster", required = false) String cluster,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    String configId = toConfigId(flagKey);
    String resolvedAppId = resolveAppId(appId, appIdHeader);
    ControlPlaneAppConfigRecord current =
        mutationService
            .findControlPlaneConfig(
                FEATURE_FLAG_OWNER_TYPE, resolvedAppId, FEATURE_FLAG_NAMESPACE, configId)
            .filter(record -> record.group().startsWith(FEATURE_FLAG_GROUP))
            .filter(record -> !hasText(environment) || environment.equals(record.env()))
            .filter(record -> !hasText(cluster) || cluster.equals(record.cluster()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feature Flag 不存在"));
    mutationService.delete(
        new ConfigMutationCommand(
            ConfigMutationAction.DELETE,
            configId,
            current.configName(),
            FEATURE_FLAG_OWNER_TYPE,
            resolvedAppId,
            FEATURE_FLAG_NAMESPACE,
            current.group(),
            "json",
            "FILE",
            false,
            current.description(),
            current.env(),
            DEFAULT_SCOPE,
            DEFAULT_SCOPE,
            current.cluster(),
            "INHERITABLE",
            "",
            resolveOperator(null, operatorHeader),
            "control plane delete feature flag"));
    return ResponseEntity.noContent().build();
  }

  private FeatureFlagResponse findSavedRecord(String appId, String configId) {
    return mutationService
        .findControlPlaneConfig(FEATURE_FLAG_OWNER_TYPE, appId, FEATURE_FLAG_NAMESPACE, configId)
        .filter(record -> record.group().startsWith(FEATURE_FLAG_GROUP))
        .map(this::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feature Flag 不存在"));
  }

  private ConfigMutationCommand toCommand(
      ConfigMutationAction action,
      String flagKey,
      FeatureFlagRequest request,
      String appIdHeader,
      String operatorHeader) {
    String content = normalizeContent(flagKey, request);
    return new ConfigMutationCommand(
        action,
        toConfigId(flagKey),
        toConfigName(flagKey),
        FEATURE_FLAG_OWNER_TYPE,
        resolveAppId(request.appId(), appIdHeader),
        FEATURE_FLAG_NAMESPACE,
        resolveGroup(request.group()),
        "json",
        "FILE",
        false,
        defaultText(request.description(), ""),
        request.environment().trim(),
        DEFAULT_SCOPE,
        DEFAULT_SCOPE,
        defaultText(request.cluster(), DEFAULT_SCOPE),
        "INHERITABLE",
        content,
        resolveOperator(request.updatedBy(), operatorHeader),
        defaultText(
            request.reason(),
            "control plane feature flag " + action.name().toLowerCase(Locale.ROOT)));
  }

  private String normalizeContent(String flagKey, FeatureFlagRequest request) {
    try {
      if (hasText(request.content())) {
        return featureFlagValidator.validateAndNormalize(request.content(), flagKey);
      }
      ObjectNode root = objectMapper.createObjectNode();
      root.put("key", flagKey);
      root.put("type", requireText(request.type(), "type").toUpperCase(Locale.ROOT));
      root.put("enabled", request.enabled() == null || request.enabled());
      root.set("defaultValue", requireJsonNode(request.defaultValue(), "defaultValue"));
      if (request.rules() != null) {
        root.set("rules", request.rules());
      } else {
        root.set("rules", objectMapper.createArrayNode());
      }
      if (request.variants() != null) {
        root.set("variants", request.variants());
      }
      if (request.rollout() != null) {
        root.set("rollout", request.rollout());
      }
      return featureFlagValidator.validateAndNormalize(
          objectMapper.writeValueAsString(root), flagKey);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feature Flag 内容不是合法 JSON", ex);
    }
  }

  private FeatureFlagResponse toResponse(ControlPlaneAppConfigRecord record) {
    try {
      FeatureFlagDocument document = featureFlagValidator.read(record.content());
      String status = "PUBLISHED".equals(record.releaseStatus()) ? "published" : "draft";
      return new FeatureFlagResponse(
          record.configId(),
          record.appId(),
          document.key(),
          record.configName(),
          defaultText(record.description(), ""),
          record.env(),
          record.cluster(),
          record.group(),
          document.type(),
          document.enabled(),
          document.defaultValue(),
          document.rules(),
          document.variants(),
          document.rollout(),
          record.content(),
          "v" + record.version(),
          status,
          record.updatedBy(),
          formatTime(record.updatedAt()),
          formatTime(record.publishedAt()));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
    }
  }

  private String resolveFlagKey(String requestKey, String pathFlagKey) {
    try {
      String resolved =
          hasText(requestKey)
              ? featureFlagValidator.normalizeKey(requestKey)
              : pathFlagKeyToKey(pathFlagKey);
      if (hasText(pathFlagKey)) {
        String pathKey = pathFlagKeyToKey(pathFlagKey);
        if (!resolved.equals(pathKey)) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feature Flag key 与路径不一致");
        }
      }
      return resolved;
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  private String pathFlagKeyToKey(String flagKey) {
    String normalized = featureFlagValidator.normalizeKey(flagKey);
    if (normalized.startsWith(FEATURE_FLAG_CONFIG_ID_PREFIX)) {
      return normalized.substring(FEATURE_FLAG_CONFIG_ID_PREFIX.length());
    }
    return normalized;
  }

  private String toConfigId(String flagKey) {
    String key = pathFlagKeyToKey(flagKey);
    return FEATURE_FLAG_CONFIG_ID_PREFIX + key;
  }

  private String toConfigName(String flagKey) {
    return pathFlagKeyToKey(flagKey) + ".json";
  }

  private String resolveGroup(String group) {
    String resolved = defaultText(group, FEATURE_FLAG_GROUP);
    if (!resolved.equals(FEATURE_FLAG_GROUP) && !resolved.startsWith(FEATURE_FLAG_GROUP + ".")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Feature Flag group 必须以 feature-flags 开头");
    }
    return resolved;
  }

  private void validateRequestId(String requestId, String expectedConfigId) {
    if (hasText(requestId) && !expectedConfigId.equals(requestId.trim())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Feature Flag id 必须等于 " + expectedConfigId);
    }
  }

  private JsonNode requireJsonNode(JsonNode node, String fieldName) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      throw new IllegalArgumentException("feature flag " + fieldName + " must be provided");
    }
    return node;
  }

  private String resolveAppId(String appId, String appIdHeader) {
    if (hasText(appId)) {
      return appId.trim();
    }
    if (hasText(appIdHeader)) {
      return appIdHeader.trim();
    }
    return DEFAULT_APP_ID;
  }

  private static String resolveOperator(String updatedBy, String operatorHeader) {
    if (hasText(updatedBy)) {
      return updatedBy.trim();
    }
    if (hasText(operatorHeader)) {
      return operatorHeader.trim();
    }
    return DEFAULT_OPERATOR;
  }

  private static String requireText(String value, String fieldName) {
    if (!hasText(value)) {
      throw new IllegalArgumentException("feature flag " + fieldName + " must not be blank");
    }
    return value.trim();
  }

  private static String formatTime(OffsetDateTime time) {
    return time == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time);
  }

  private static String defaultText(String value, String defaultValue) {
    return hasText(value) ? value.trim() : defaultValue;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record FeatureFlagRequest(
      String id,
      String appId,
      String key,
      String description,
      @NotBlank String environment,
      @NotBlank String cluster,
      String group,
      String type,
      Boolean enabled,
      JsonNode defaultValue,
      JsonNode rules,
      JsonNode variants,
      JsonNode rollout,
      String content,
      String updatedBy,
      String reason) {}

  public record FeatureFlagResponse(
      String id,
      String appId,
      String key,
      String name,
      String description,
      String environment,
      String cluster,
      String group,
      String type,
      boolean enabled,
      JsonNode defaultValue,
      JsonNode rules,
      JsonNode variants,
      JsonNode rollout,
      String content,
      String version,
      String status,
      String updatedBy,
      String updatedAt,
      String publishedAt) {}

  public record FeatureFlagListResponse(List<FeatureFlagResponse> records) {}
}

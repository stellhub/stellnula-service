package io.github.stellnula.api.http;

import io.github.stellnula.application.ConfigMutationService;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
@RequestMapping("/api/v1/control-plane/common-config")
public class ControlPlaneCommonConfigController {

  private static final String DEFAULT_OWNER_ID = "global";
  private static final String DEFAULT_OPERATOR = "config-control-plane";
  private static final String DEFAULT_SCOPE = "default";
  private static final String COMMON_CONFIG_OWNER_TYPE = "PUBLIC";
  private static final String COMMON_CONFIG_NAMESPACE = "common-config";
  private static final Set<String> SUPPORTED_FORMATS =
      Set.of("yaml", "properties", "json", "toml", "text");

  private final ConfigMutationService mutationService;

  /** 查询公共配置范围选项。 */
  @GetMapping("/scope")
  public ConfigScopeResponse scope(
      @RequestParam(name = "ownerId", required = false) String ownerId,
      @RequestHeader(name = "X-Stell-Public-Owner-Id", required = false) String ownerIdHeader) {
    List<ControlPlaneAppConfigRecord> records =
        mutationService.findControlPlaneConfigs(
            COMMON_CONFIG_OWNER_TYPE,
            resolveOwnerId(ownerId, ownerIdHeader),
            COMMON_CONFIG_NAMESPACE,
            "",
            "",
            "");
    List<String> environments =
        records.stream()
            .map(ControlPlaneAppConfigRecord::env)
            .filter(ControlPlaneCommonConfigController::hasText)
            .distinct()
            .sorted()
            .toList();
    List<String> groups =
        records.stream()
            .map(ControlPlaneAppConfigRecord::group)
            .filter(ControlPlaneCommonConfigController::hasText)
            .distinct()
            .sorted()
            .toList();
    Map<String, List<String>> clustersByEnvironment =
        records.stream()
            .collect(
                Collectors.groupingBy(
                    ControlPlaneAppConfigRecord::env,
                    Collectors.mapping(
                        ControlPlaneAppConfigRecord::cluster,
                        Collectors.collectingAndThen(
                            Collectors.toSet(),
                            clusters ->
                                clusters.stream()
                                    .filter(ControlPlaneCommonConfigController::hasText)
                                    .sorted()
                                    .toList()))));
    return new ConfigScopeResponse(environments, clustersByEnvironment, groups);
  }

  /** 查询公共配置列表。 */
  @GetMapping
  public CommonConfigListResponse listConfigs(
      @RequestParam(name = "ownerId", required = false) String ownerId,
      @RequestParam(name = "environment", required = false) String environment,
      @RequestParam(name = "cluster", required = false) String cluster,
      @RequestParam(name = "group", required = false) String group,
      @RequestHeader(name = "X-Stell-Public-Owner-Id", required = false) String ownerIdHeader) {
    List<CommonConfigResponse> records =
        mutationService
            .findControlPlaneConfigs(
                COMMON_CONFIG_OWNER_TYPE,
                resolveOwnerId(ownerId, ownerIdHeader),
                COMMON_CONFIG_NAMESPACE,
                environment,
                cluster,
                group)
            .stream()
            .map(ControlPlaneCommonConfigController::toResponse)
            .toList();
    return new CommonConfigListResponse(records);
  }

  /** 查询公共配置详情。 */
  @GetMapping("/{configId}")
  public CommonConfigResponse getConfig(
      @PathVariable("configId") @NotBlank String configId,
      @RequestParam(name = "ownerId", required = false) String ownerId,
      @RequestHeader(name = "X-Stell-Public-Owner-Id", required = false) String ownerIdHeader) {
    return mutationService
        .findControlPlaneConfig(
            COMMON_CONFIG_OWNER_TYPE,
            resolveOwnerId(ownerId, ownerIdHeader),
            COMMON_CONFIG_NAMESPACE,
            configId)
        .map(ControlPlaneCommonConfigController::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
  }

  /** 创建公共配置草稿。 */
  @PostMapping
  public ResponseEntity<CommonConfigResponse> createConfig(
      @Valid @RequestBody CommonConfigRequest request,
      @RequestHeader(name = "X-Stell-Public-Owner-Id", required = false) String ownerIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    String configId = hasText(request.id()) ? request.id().trim() : "config-" + UUID.randomUUID();
    mutationService.saveDraft(
        toCommand(ConfigMutationAction.UPSERT, configId, request, ownerIdHeader, operatorHeader));
    CommonConfigResponse body =
        findSavedRecord(resolveOwnerId(request.ownerId(), ownerIdHeader), configId);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{configId}")
            .buildAndExpand(configId)
            .toUri();
    return ResponseEntity.created(location).body(body);
  }

  /** 保存公共配置草稿。 */
  @PutMapping("/{configId}")
  public CommonConfigResponse saveDraft(
      @PathVariable("configId") @NotBlank String configId,
      @Valid @RequestBody CommonConfigRequest request,
      @RequestHeader(name = "X-Stell-Public-Owner-Id", required = false) String ownerIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    mutationService.saveDraft(
        toCommand(ConfigMutationAction.UPSERT, configId, request, ownerIdHeader, operatorHeader));
    return findSavedRecord(resolveOwnerId(request.ownerId(), ownerIdHeader), configId);
  }

  /** 发布公共配置。 */
  @PostMapping("/{configId}/publish")
  public CommonConfigResponse publish(
      @PathVariable("configId") @NotBlank String configId,
      @Valid @RequestBody CommonConfigRequest request,
      @RequestHeader(name = "X-Stell-Public-Owner-Id", required = false) String ownerIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    mutationService.upsert(
        toCommand(ConfigMutationAction.UPSERT, configId, request, ownerIdHeader, operatorHeader));
    return findSavedRecord(resolveOwnerId(request.ownerId(), ownerIdHeader), configId);
  }

  /** 删除公共配置。 */
  @DeleteMapping("/{configId}")
  public ResponseEntity<Void> deleteConfig(
      @PathVariable("configId") @NotBlank String configId,
      @RequestParam(name = "ownerId", required = false) String ownerId,
      @RequestParam(name = "environment", required = false) String environment,
      @RequestParam(name = "cluster", required = false) String cluster,
      @RequestHeader(name = "X-Stell-Public-Owner-Id", required = false) String ownerIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    String resolvedOwnerId = resolveOwnerId(ownerId, ownerIdHeader);
    ControlPlaneAppConfigRecord current =
        mutationService
            .findControlPlaneConfig(
                COMMON_CONFIG_OWNER_TYPE, resolvedOwnerId, COMMON_CONFIG_NAMESPACE, configId)
            .filter(record -> !hasText(environment) || environment.equals(record.env()))
            .filter(record -> !hasText(cluster) || cluster.equals(record.cluster()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
    mutationService.delete(
        new ConfigMutationCommand(
            ConfigMutationAction.DELETE,
            configId,
            current.configName(),
            COMMON_CONFIG_OWNER_TYPE,
            resolvedOwnerId,
            COMMON_CONFIG_NAMESPACE,
            current.group(),
            current.format(),
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
            "control plane delete common config"));
    return ResponseEntity.noContent().build();
  }

  private CommonConfigResponse findSavedRecord(String ownerId, String configId) {
    return mutationService
        .findControlPlaneConfig(
            COMMON_CONFIG_OWNER_TYPE, ownerId, COMMON_CONFIG_NAMESPACE, configId)
        .map(ControlPlaneCommonConfigController::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
  }

  private ConfigMutationCommand toCommand(
      ConfigMutationAction action,
      String configId,
      CommonConfigRequest request,
      String ownerIdHeader,
      String operatorHeader) {
    String format = normalizeFormat(request.format());
    return new ConfigMutationCommand(
        action,
        configId,
        request.name().trim(),
        COMMON_CONFIG_OWNER_TYPE,
        resolveOwnerId(request.ownerId(), ownerIdHeader),
        COMMON_CONFIG_NAMESPACE,
        defaultText(request.group(), DEFAULT_SCOPE),
        format,
        "FILE",
        false,
        defaultText(request.description(), ""),
        request.environment().trim(),
        DEFAULT_SCOPE,
        DEFAULT_SCOPE,
        defaultText(request.cluster(), DEFAULT_SCOPE),
        "INHERITABLE",
        request.content(),
        resolveOperator(request.updatedBy(), operatorHeader),
        "control plane common config " + action.name().toLowerCase(Locale.ROOT));
  }

  private static CommonConfigResponse toResponse(ControlPlaneAppConfigRecord record) {
    String status = "PUBLISHED".equals(record.releaseStatus()) ? "published" : "draft";
    return new CommonConfigResponse(
        record.configId(),
        record.appId(),
        record.configName(),
        defaultText(record.description(), ""),
        record.env(),
        record.cluster(),
        record.group(),
        record.format(),
        record.formatLocked(),
        record.content(),
        "v" + record.version(),
        status,
        record.updatedBy(),
        formatTime(record.updatedAt()),
        formatTime(record.publishedAt()));
  }

  private String normalizeFormat(String format) {
    String normalized = defaultText(format, "yaml").toLowerCase(Locale.ROOT);
    if (!SUPPORTED_FORMATS.contains(normalized)) {
      throw new IllegalArgumentException("不支持的配置格式: " + format);
    }
    return normalized;
  }

  private String resolveOwnerId(String ownerId, String ownerIdHeader) {
    if (hasText(ownerId)) {
      return ownerId.trim();
    }
    if (hasText(ownerIdHeader)) {
      return ownerIdHeader.trim();
    }
    return DEFAULT_OWNER_ID;
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

  private static String formatTime(OffsetDateTime time) {
    return time == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time);
  }

  private static String defaultText(String value, String defaultValue) {
    return hasText(value) ? value.trim() : defaultValue;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record CommonConfigRequest(
      String id,
      String ownerId,
      @NotBlank String name,
      String description,
      @NotBlank String environment,
      @NotBlank String cluster,
      String group,
      @NotBlank String format,
      @NotBlank String content,
      String updatedBy) {}

  public record CommonConfigResponse(
      String id,
      String ownerId,
      String name,
      String description,
      String environment,
      String cluster,
      String group,
      String format,
      boolean formatLocked,
      String content,
      String version,
      String status,
      String updatedBy,
      String updatedAt,
      String publishedAt) {}

  public record CommonConfigListResponse(List<CommonConfigResponse> records) {}

  public record ConfigScopeResponse(
      List<String> environments,
      Map<String, List<String>> clustersByEnvironment,
      List<String> groups) {}
}

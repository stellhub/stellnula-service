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
@RequestMapping("/api/v1/control-plane")
public class ControlPlaneAppConfigController {

  private static final String DEFAULT_APP_ID = "stellhub.core.middleware.stellcloud.admin";
  private static final String DEFAULT_OPERATOR = "config-control-plane";
  private static final String DEFAULT_SCOPE = "default";
  private static final String APP_CONFIG_OWNER_TYPE = "APPLICATION";
  private static final String APP_CONFIG_NAMESPACE = "app-config";
  private static final Set<String> SUPPORTED_FORMATS =
      Set.of("yaml", "properties", "json", "toml", "text");

  private final ConfigMutationService mutationService;

  /** 查询控制面到配置中心数据面的健康状态。 */
  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "component", "stellnula-service");
  }

  /** 查询当前应用下的配置范围选项。 */
  @GetMapping("/app-config/scope")
  public ConfigScopeResponse scope(
      @RequestParam(name = "appId", required = false) String appId,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader) {
    List<ControlPlaneAppConfigRecord> records =
        mutationService.findControlPlaneConfigs(
            APP_CONFIG_OWNER_TYPE,
            resolveAppId(appId, appIdHeader),
            APP_CONFIG_NAMESPACE,
            "",
            "",
            "");
    List<String> environments =
        records.stream()
            .map(ControlPlaneAppConfigRecord::env)
            .filter(ControlPlaneAppConfigController::hasText)
            .distinct()
            .sorted()
            .toList();
    List<String> groups =
        records.stream()
            .map(ControlPlaneAppConfigRecord::group)
            .filter(ControlPlaneAppConfigController::hasText)
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
                                    .filter(ControlPlaneAppConfigController::hasText)
                                    .sorted()
                                    .toList()))));
    return new ConfigScopeResponse(environments, clustersByEnvironment, groups);
  }

  /** 查询应用配置列表。 */
  @GetMapping("/app-config")
  public AppConfigListResponse listConfigs(
      @RequestParam(name = "appId", required = false) String appId,
      @RequestParam(name = "environment", required = false) String environment,
      @RequestParam(name = "cluster", required = false) String cluster,
      @RequestParam(name = "group", required = false) String group,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader) {
    List<AppConfigResponse> records =
        mutationService
            .findControlPlaneConfigs(
                APP_CONFIG_OWNER_TYPE,
                resolveAppId(appId, appIdHeader),
                APP_CONFIG_NAMESPACE,
                environment,
                cluster,
                group)
            .stream()
            .map(ControlPlaneAppConfigController::toResponse)
            .toList();
    return new AppConfigListResponse(records);
  }

  /** 查询应用配置详情。 */
  @GetMapping("/app-config/{configId}")
  public AppConfigResponse getConfig(
      @PathVariable("configId") @NotBlank String configId,
      @RequestParam(name = "appId", required = false) String appId,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader) {
    return mutationService
        .findControlPlaneConfig(
            APP_CONFIG_OWNER_TYPE, resolveAppId(appId, appIdHeader), APP_CONFIG_NAMESPACE, configId)
        .map(ControlPlaneAppConfigController::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
  }

  /** 创建应用配置草稿。 */
  @PostMapping("/app-config")
  public ResponseEntity<AppConfigResponse> createConfig(
      @Valid @RequestBody AppConfigRequest request,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    String configId = hasText(request.id()) ? request.id().trim() : "config-" + UUID.randomUUID();
    mutationService.saveDraft(
        toCommand(ConfigMutationAction.UPSERT, configId, request, appIdHeader, operatorHeader));
    AppConfigResponse body = findSavedRecord(resolveAppId(request.appId(), appIdHeader), configId);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{configId}")
            .buildAndExpand(configId)
            .toUri();
    return ResponseEntity.created(location).body(body);
  }

  /** 保存应用配置草稿。 */
  @PutMapping("/app-config/{configId}")
  public AppConfigResponse saveDraft(
      @PathVariable("configId") @NotBlank String configId,
      @Valid @RequestBody AppConfigRequest request,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    mutationService.saveDraft(
        toCommand(ConfigMutationAction.UPSERT, configId, request, appIdHeader, operatorHeader));
    return findSavedRecord(resolveAppId(request.appId(), appIdHeader), configId);
  }

  /** 发布应用配置。 */
  @PostMapping("/app-config/{configId}/publish")
  public AppConfigResponse publish(
      @PathVariable("configId") @NotBlank String configId,
      @Valid @RequestBody AppConfigRequest request,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    mutationService.upsert(
        toCommand(ConfigMutationAction.UPSERT, configId, request, appIdHeader, operatorHeader));
    return findSavedRecord(resolveAppId(request.appId(), appIdHeader), configId);
  }

  /** 删除应用配置。 */
  @DeleteMapping("/app-config/{configId}")
  public ResponseEntity<Void> deleteConfig(
      @PathVariable("configId") @NotBlank String configId,
      @RequestParam(name = "appId", required = false) String appId,
      @RequestParam(name = "environment", required = false) String environment,
      @RequestParam(name = "cluster", required = false) String cluster,
      @RequestHeader(name = "X-Stell-App-Id", required = false) String appIdHeader,
      @RequestHeader(name = "X-Operator", required = false) String operatorHeader) {
    String resolvedAppId = resolveAppId(appId, appIdHeader);
    ControlPlaneAppConfigRecord current =
        mutationService
            .findControlPlaneConfig(
                APP_CONFIG_OWNER_TYPE, resolvedAppId, APP_CONFIG_NAMESPACE, configId)
            .filter(record -> !hasText(environment) || environment.equals(record.env()))
            .filter(record -> !hasText(cluster) || cluster.equals(record.cluster()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
    mutationService.delete(
        new ConfigMutationCommand(
            ConfigMutationAction.DELETE,
            configId,
            current.configName(),
            APP_CONFIG_OWNER_TYPE,
            resolvedAppId,
            APP_CONFIG_NAMESPACE,
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
            "control plane delete app config"));
    return ResponseEntity.noContent().build();
  }

  private AppConfigResponse findSavedRecord(String appId, String configId) {
    return mutationService
        .findControlPlaneConfig(APP_CONFIG_OWNER_TYPE, appId, APP_CONFIG_NAMESPACE, configId)
        .map(ControlPlaneAppConfigController::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
  }

  private ConfigMutationCommand toCommand(
      ConfigMutationAction action,
      String configId,
      AppConfigRequest request,
      String appIdHeader,
      String operatorHeader) {
    String format = normalizeFormat(request.format());
    return new ConfigMutationCommand(
        action,
        configId,
        request.name().trim(),
        APP_CONFIG_OWNER_TYPE,
        resolveAppId(request.appId(), appIdHeader),
        APP_CONFIG_NAMESPACE,
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
        "control plane app config " + action.name().toLowerCase(Locale.ROOT));
  }

  private static AppConfigResponse toResponse(ControlPlaneAppConfigRecord record) {
    String status = "PUBLISHED".equals(record.releaseStatus()) ? "published" : "draft";
    return new AppConfigResponse(
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

  private static String formatTime(OffsetDateTime time) {
    return time == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time);
  }

  private static String defaultText(String value, String defaultValue) {
    return hasText(value) ? value.trim() : defaultValue;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record AppConfigRequest(
      String id,
      String appId,
      @NotBlank String name,
      String description,
      @NotBlank String environment,
      @NotBlank String cluster,
      String group,
      @NotBlank String format,
      @NotBlank String content,
      String updatedBy) {}

  public record AppConfigResponse(
      String id,
      String appId,
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

  public record AppConfigListResponse(List<AppConfigResponse> records) {}

  public record ConfigScopeResponse(
      List<String> environments,
      Map<String, List<String>> clustersByEnvironment,
      List<String> groups) {}
}

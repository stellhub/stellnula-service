package io.github.stellnula.api.http;

import io.github.stellnula.application.ConfigGrayRuleService;
import io.github.stellnula.domain.ConfigGrayImpactClient;
import io.github.stellnula.domain.ConfigGrayMutationCommand;
import io.github.stellnula.domain.ConfigGrayMutationResult;
import io.github.stellnula.domain.ConfigGrayRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/configs/{configId}/gray-rules")
public class ConfigGrayRuleController {

  private static final String CONTROL_PLANE_OPERATOR = "config-control-plane";

  private final ConfigGrayRuleService grayRuleService;

  /** 查询配置灰度规则。 */
  @GetMapping("/{grayName}")
  public GrayRuleResponse get(
      @PathVariable @NotBlank String configId,
      @PathVariable @NotBlank String grayName,
      @RequestParam @NotBlank String env,
      @RequestParam(defaultValue = "default") String region,
      @RequestParam(defaultValue = "default") String zone,
      @RequestParam(defaultValue = "default") String cluster) {
    return grayRuleService
        .findLatest(configId, grayName, env, region, zone, cluster)
        .map(GrayRuleResponse::from)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gray rule not found"));
  }

  /** 查询灰度规则当前影响面。 */
  @GetMapping("/{grayName}/impact")
  public GrayImpactResponse impact(
      @PathVariable @NotBlank String configId,
      @PathVariable @NotBlank String grayName,
      @RequestParam @NotBlank String env,
      @RequestParam(defaultValue = "default") String region,
      @RequestParam(defaultValue = "default") String zone,
      @RequestParam(defaultValue = "default") String cluster,
      @RequestParam(defaultValue = "1000") int limit) {
    List<GrayImpactClientResponse> clients =
        grayRuleService
            .findImpactClients(configId, grayName, env, region, zone, cluster, limit)
            .stream()
            .map(GrayImpactClientResponse::from)
            .toList();
    return new GrayImpactResponse(
        configId, grayName, env, region, zone, cluster, clients.size(), clients);
  }

  /** 创建、更新或发布配置灰度规则。 */
  @PutMapping("/{grayName}")
  public GrayMutationResponse upsert(
      @PathVariable @NotBlank String configId,
      @PathVariable @NotBlank String grayName,
      @RequestHeader(name = "X-Operator", required = false) String operator,
      @Valid @RequestBody GrayRuleRequest request) {
    ConfigGrayMutationResult result =
        grayRuleService.upsert(
            request.toCommand(configId, grayName, defaultText(operator, CONTROL_PLANE_OPERATOR)));
    return GrayMutationResponse.from(result);
  }

  /** 结束配置灰度规则。 */
  @DeleteMapping("/{grayName}")
  public GrayMutationResponse end(
      @PathVariable @NotBlank String configId,
      @PathVariable @NotBlank String grayName,
      @RequestHeader(name = "X-Operator", required = false) String operator,
      @Valid @RequestBody GrayRuleEndRequest request) {
    ConfigGrayMutationResult result =
        grayRuleService.end(
            request.toCommand(configId, grayName, defaultText(operator, CONTROL_PLANE_OPERATOR)));
    return GrayMutationResponse.from(result);
  }

  public record GrayRuleRequest(
      @NotBlank String env,
      String region,
      String zone,
      String cluster,
      @NotBlank String ruleType,
      @NotBlank String grayRules,
      @NotBlank String configValue,
      int priority,
      String status,
      OffsetDateTime startTime,
      OffsetDateTime endTime,
      String reason) {

    ConfigGrayMutationCommand toCommand(String configId, String grayName, String operator) {
      return new ConfigGrayMutationCommand(
          configId,
          grayName,
          env,
          region,
          zone,
          cluster,
          ruleType,
          grayRules,
          configValue,
          priority,
          status,
          startTime,
          endTime,
          operator,
          defaultText(reason, "gray rule upsert"));
    }
  }

  public record GrayRuleEndRequest(
      @NotBlank String env,
      String region,
      String zone,
      String cluster,
      String ruleType,
      String grayRules,
      String configValue,
      int priority,
      OffsetDateTime endTime,
      String reason) {

    ConfigGrayMutationCommand toCommand(String configId, String grayName, String operator) {
      return new ConfigGrayMutationCommand(
          configId,
          grayName,
          env,
          region,
          zone,
          cluster,
          defaultText(ruleType, "TAG"),
          defaultText(grayRules, "{\"type\":\"TAG\",\"op\":\"MATCH_ALL\",\"values\":{}}"),
          defaultText(configValue, ""),
          priority,
          "ENDED",
          null,
          endTime,
          operator,
          defaultText(reason, "gray rule end"));
    }
  }

  public record GrayMutationResponse(
      long grayRuleId,
      String configId,
      long scopeId,
      String grayName,
      long grayVersion,
      long effectiveRevision,
      String status,
      String checksum,
      OffsetDateTime updatedAt) {

    static GrayMutationResponse from(ConfigGrayMutationResult result) {
      return new GrayMutationResponse(
          result.grayRuleId(),
          result.configId(),
          result.scopeId(),
          result.grayName(),
          result.grayVersion(),
          result.effectiveRevision(),
          result.status(),
          result.checksum(),
          result.updatedAt());
    }
  }

  public record GrayRuleResponse(
      long grayRuleId,
      String configId,
      long scopeId,
      String grayName,
      String ruleType,
      String grayRules,
      String configValue,
      long grayVersion,
      long effectiveRevision,
      String checksum,
      int priority,
      String status,
      OffsetDateTime startTime,
      OffsetDateTime endTime,
      OffsetDateTime updatedAt) {

    static GrayRuleResponse from(ConfigGrayRecord record) {
      return new GrayRuleResponse(
          record.id(),
          record.configId(),
          record.scopeId(),
          record.grayName(),
          record.ruleType(),
          record.grayRules(),
          record.configValue(),
          record.grayVersion(),
          record.effectiveRevision(),
          record.checksum(),
          record.priority(),
          record.status(),
          record.startTime(),
          record.endTime(),
          record.updatedAt());
    }
  }

  public record GrayImpactResponse(
      String configId,
      String grayName,
      String env,
      String region,
      String zone,
      String cluster,
      int matchedCount,
      List<GrayImpactClientResponse> clients) {}

  public record GrayImpactClientResponse(
      String appId,
      String clientId,
      String env,
      String region,
      String zone,
      String cluster,
      String namespaceCode,
      String groupCode,
      String clientIp,
      String hostName,
      String sdkVersion,
      Map<String, String> labels,
      OffsetDateTime lastSeenAt) {

    static GrayImpactClientResponse from(ConfigGrayImpactClient client) {
      return new GrayImpactClientResponse(
          client.appId(),
          client.clientId(),
          client.env(),
          client.region(),
          client.zone(),
          client.cluster(),
          client.namespaceCode(),
          client.groupCode(),
          client.clientIp(),
          client.hostName(),
          client.sdkVersion(),
          client.labels(),
          client.lastSeenAt());
    }
  }

  private static String defaultText(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}

package io.github.stellnula.api.http;

import io.github.stellnula.application.ConfigMutationService;
import io.github.stellnula.application.SensitiveConfigCodec;
import io.github.stellnula.domain.ConfigMutationAction;
import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ConfigMutationResult;
import io.github.stellnula.domain.ConfigRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/configs")
public class ConfigManagementController {

  private static final String CONTROL_PLANE_OPERATOR = "config-control-plane";

  private final ConfigMutationService mutationService;
  private final SensitiveConfigCodec sensitiveConfigCodec;

  /** 查询指定配置的最新版本。 */
  @GetMapping("/{configId}")
  public ConfigResponse get(
      @PathVariable @NotBlank String configId,
      @RequestParam @NotBlank String env,
      @RequestParam(defaultValue = "default") String region,
      @RequestParam(defaultValue = "default") String zone,
      @RequestParam(defaultValue = "default") String cluster,
      @RequestHeader(name = "X-Sensitive-Plaintext", required = false) String sensitivePlaintext) {
    return mutationService
        .findLatest(configId, env, region, zone, cluster)
        .map(
            record ->
                ConfigResponse.from(
                    record, allowPlaintext(sensitivePlaintext), sensitiveConfigCodec))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found"));
  }

  /** 新增或更新应用配置、公共配置或服务治理规则配置。 */
  @PutMapping("/{configId}")
  public MutationResponse upsert(
      @PathVariable @NotBlank String configId,
      @RequestHeader(name = "X-Operator", required = false) String operator,
      @Valid @RequestBody ConfigRequest request) {
    ConfigMutationResult result =
        mutationService.upsert(
            request.toCommand(configId, defaultText(operator, CONTROL_PLANE_OPERATOR)));
    return MutationResponse.from(result);
  }

  /** 删除指定配置作用域下的最新可见版本。 */
  @DeleteMapping("/{configId}")
  public MutationResponse delete(
      @PathVariable @NotBlank String configId,
      @RequestHeader(name = "X-Operator", required = false) String operator,
      @Valid @RequestBody ConfigDeleteRequest request) {
    ConfigMutationResult result =
        mutationService.delete(
            request.toCommand(configId, defaultText(operator, CONTROL_PLANE_OPERATOR)));
    return MutationResponse.from(result);
  }

  /** 将公共配置从一个环境手动复制发布到另一个环境。 */
  @PostMapping("/{configId}/replications")
  public MutationResponse replicatePublicConfig(
      @PathVariable @NotBlank String configId,
      @RequestHeader(name = "X-Operator", required = false) String operator,
      @Valid @RequestBody PublicConfigReplicationRequest request) {
    ConfigRecord source =
        mutationService
            .findLatest(
                configId,
                request.sourceEnv(),
                request.sourceRegion(),
                request.sourceZone(),
                request.sourceCluster())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source config not found"));
    if (!"PUBLIC".equals(source.ownerType())) {
      throw new IllegalArgumentException(
          "only PUBLIC config can be replicated across environments");
    }
    if (source.env().equals(request.targetEnv())) {
      throw new IllegalArgumentException("targetEnv must be different from sourceEnv");
    }
    ConfigMutationResult result =
        mutationService.upsert(
            request.toCommand(source, defaultText(operator, CONTROL_PLANE_OPERATOR)));
    return MutationResponse.from(result);
  }

  public record ConfigRequest(
      String configName,
      String ownerType,
      @NotBlank String ownerId,
      String namespace,
      String group,
      String contentType,
      boolean sensitive,
      String description,
      @NotBlank String env,
      String region,
      String zone,
      String cluster,
      String scopeMode,
      @NotBlank String content,
      String reason) {

    ConfigMutationCommand toCommand(String configId, String operator) {
      return new ConfigMutationCommand(
          ConfigMutationAction.UPSERT,
          configId,
          configName,
          ownerType,
          ownerId,
          namespace,
          group,
          contentType,
          sensitive,
          description,
          env,
          region,
          zone,
          cluster,
          scopeMode,
          content,
          operator,
          defaultText(reason, "config upsert"));
    }
  }

  public record ConfigDeleteRequest(
      String configName,
      String ownerType,
      @NotBlank String ownerId,
      String namespace,
      String group,
      String contentType,
      boolean sensitive,
      String description,
      @NotBlank String env,
      String region,
      String zone,
      String cluster,
      String scopeMode,
      String reason) {

    ConfigMutationCommand toCommand(String configId, String operator) {
      return new ConfigMutationCommand(
          ConfigMutationAction.DELETE,
          configId,
          configName,
          ownerType,
          ownerId,
          namespace,
          group,
          contentType,
          sensitive,
          description,
          env,
          region,
          zone,
          cluster,
          scopeMode,
          "",
          operator,
          defaultText(reason, "config delete"));
    }
  }

  public record PublicConfigReplicationRequest(
      @NotBlank String sourceEnv,
      String sourceRegion,
      String sourceZone,
      String sourceCluster,
      @NotBlank String targetEnv,
      String targetRegion,
      String targetZone,
      String targetCluster,
      String reason) {

    ConfigMutationCommand toCommand(ConfigRecord source, String operator) {
      return new ConfigMutationCommand(
          ConfigMutationAction.UPSERT,
          source.configId(),
          source.configName(),
          source.ownerType(),
          source.ownerId(),
          source.namespaceCode(),
          source.groupCode(),
          source.contentType(),
          source.sensitive(),
          "Replicated from env " + source.env(),
          targetEnv,
          targetRegion,
          targetZone,
          targetCluster,
          "REPLICATED",
          source.content(),
          operator,
          defaultText(reason, "public config replicated from " + source.env()));
    }
  }

  public record MutationResponse(
      String configId,
      long scopeId,
      String releaseNo,
      long version,
      long revision,
      String releaseStatus,
      String checksum) {

    static MutationResponse from(ConfigMutationResult result) {
      return new MutationResponse(
          result.configId(),
          result.scopeId(),
          result.releaseNo(),
          result.version(),
          result.revision(),
          result.releaseStatus(),
          result.checksum());
    }
  }

  public record ConfigResponse(
      String configId,
      String configName,
      String ownerType,
      String ownerId,
      String namespace,
      String group,
      String contentType,
      boolean sensitive,
      String env,
      String region,
      String zone,
      String cluster,
      String scopeMode,
      String releaseNo,
      long version,
      long revision,
      String releaseStatus,
      String checksum,
      String content) {

    static ConfigResponse from(
        ConfigRecord record,
        boolean allowSensitivePlaintext,
        SensitiveConfigCodec sensitiveConfigCodec) {
      return new ConfigResponse(
          record.configId(),
          record.configName(),
          record.ownerType(),
          record.ownerId(),
          record.namespaceCode(),
          record.groupCode(),
          record.contentType(),
          record.sensitive(),
          record.env(),
          record.region(),
          record.zone(),
          record.cluster(),
          record.scopeMode(),
          record.releaseNo(),
          record.version(),
          record.revision(),
          record.releaseStatus(),
          record.checksum(),
          allowSensitivePlaintext
              ? record.content()
              : sensitiveConfigCodec.maskIfSensitive(record.sensitive(), record.content()));
    }
  }

  private boolean allowPlaintext(String sensitivePlaintext) {
    return "true".equalsIgnoreCase(sensitivePlaintext);
  }

  private static String defaultText(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}

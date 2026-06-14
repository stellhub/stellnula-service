package io.github.stellnula.api.http;

import io.github.stellnula.application.ConfigMutationService;
import io.github.stellnula.application.GovernanceRuleValidator;
import io.github.stellnula.domain.ConfigMutationAction;
import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ConfigMutationResult;
import io.github.stellnula.domain.ConfigRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/governance/rules")
public class GovernanceRuleController {

  private static final String GOVERNANCE_OPERATOR = "service-governance-data-plane";
  private static final String GOVERNANCE_NAMESPACE = "governance";
  private static final String GOVERNANCE_GROUP = "service-governance";

  private final ConfigMutationService mutationService;
  private final GovernanceRuleValidator governanceRuleValidator;

  /** 按服务治理维度查询规则。 */
  @GetMapping
  public List<GovernanceRuleResponse> list(
      @RequestParam @NotBlank String env,
      @RequestParam(defaultValue = "") String ownerId,
      @RequestParam(defaultValue = "") String ruleType,
      @RequestParam(defaultValue = "") String targetService,
      @RequestParam(defaultValue = "") String status) {
    return mutationService
        .findGovernanceRules(env, ownerId, ruleType, targetService, status)
        .stream()
        .map(GovernanceRuleResponse::from)
        .toList();
  }

  /** 查询服务治理规则最新版本。 */
  @GetMapping("/{ruleId}")
  public GovernanceRuleResponse get(
      @PathVariable @NotBlank String ruleId,
      @RequestParam @NotBlank String env,
      @RequestParam(defaultValue = "default") String region,
      @RequestParam(defaultValue = "default") String zone,
      @RequestParam(defaultValue = "default") String cluster) {
    return mutationService
        .findLatest(ruleId, env, region, zone, cluster)
        .map(GovernanceRuleResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
  }

  /** 新增或更新服务治理规则。 */
  @PutMapping("/{ruleId}")
  public MutationResponse upsert(
      @PathVariable @NotBlank String ruleId, @Valid @RequestBody GovernanceRuleRequest request) {
    ConfigMutationResult result =
        mutationService.upsert(request.toCommand(ruleId, governanceRuleValidator));
    return MutationResponse.from(result);
  }

  /** 删除服务治理规则。 */
  @DeleteMapping("/{ruleId}")
  public MutationResponse delete(
      @PathVariable @NotBlank String ruleId,
      @Valid @RequestBody GovernanceRuleDeleteRequest request) {
    ConfigMutationResult result = mutationService.delete(request.toCommand(ruleId));
    return MutationResponse.from(result);
  }

  public record GovernanceRuleRequest(
      String ruleName,
      @NotBlank String ownerId,
      String ownerType,
      @NotBlank String env,
      String region,
      String zone,
      String cluster,
      String scopeMode,
      String contentType,
      boolean sensitive,
      String description,
      @NotBlank String content,
      String reason) {

    ConfigMutationCommand toCommand(String ruleId, GovernanceRuleValidator validator) {
      String normalizedContent = validator.validateAndNormalize(content);
      return new ConfigMutationCommand(
          ConfigMutationAction.UPSERT,
          ruleId,
          ruleName,
          defaultText(ownerType, "APPLICATION"),
          ownerId,
          GOVERNANCE_NAMESPACE,
          GOVERNANCE_GROUP,
          "json",
          defaultText(contentType, "FILE"),
          false,
          description,
          env,
          region,
          zone,
          cluster,
          scopeMode,
          normalizedContent,
          GOVERNANCE_OPERATOR,
          defaultText(reason, "service governance rule upsert"));
    }
  }

  public record GovernanceRuleDeleteRequest(
      @NotBlank String ownerId,
      String ownerType,
      @NotBlank String env,
      String region,
      String zone,
      String cluster,
      String scopeMode,
      String reason) {

    ConfigMutationCommand toCommand(String ruleId) {
      return new ConfigMutationCommand(
          ConfigMutationAction.DELETE,
          ruleId,
          ruleId,
          defaultText(ownerType, "APPLICATION"),
          ownerId,
          GOVERNANCE_NAMESPACE,
          GOVERNANCE_GROUP,
          "json",
          "FILE",
          false,
          null,
          env,
          region,
          zone,
          cluster,
          scopeMode,
          "",
          GOVERNANCE_OPERATOR,
          defaultText(reason, "service governance rule delete"));
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

  public record GovernanceRuleResponse(
      String ruleId,
      String ruleName,
      String ownerType,
      String ownerId,
      String env,
      String region,
      String zone,
      String cluster,
      String contentType,
      long version,
      long revision,
      String releaseStatus,
      String checksum,
      String ruleType,
      String targetService,
      String status,
      Integer priority,
      String content) {

    static GovernanceRuleResponse from(ConfigRecord record) {
      return new GovernanceRuleResponse(
          record.configId(),
          record.configName(),
          record.ownerType(),
          record.ownerId(),
          record.env(),
          record.region(),
          record.zone(),
          record.cluster(),
          record.contentType(),
          record.version(),
          record.revision(),
          record.releaseStatus(),
          record.checksum(),
          record.governanceRuleType(),
          record.governanceTargetService(),
          record.governanceStatus(),
          record.governancePriority(),
          record.content());
    }
  }

  private static String defaultText(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}

package io.github.stellnula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.domain.GovernanceRuleIndex;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class GovernanceRuleValidator {

  private static final Set<String> SUPPORTED_RULE_TYPES =
      Set.of("ROUTE", "RATE_LIMIT", "CIRCUIT_BREAKER", "DEGRADE");
  private static final Set<String> SUPPORTED_STATUS = Set.of("DRAFT", "ACTIVE", "DISABLED");

  private final ObjectMapper objectMapper;

  public GovernanceRuleValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** 校验服务治理规则领域模型。 */
  public String validateAndNormalize(String content) {
    return validate(content).normalizedContent();
  }

  /** 校验服务治理规则领域模型并提取索引字段。 */
  public ValidatedGovernanceRule validate(String content) {
    try {
      JsonNode root = objectMapper.readTree(content);
      String ruleType = requireEnum(root, "ruleType", SUPPORTED_RULE_TYPES);
      String targetService = requireText(root, "targetService");
      String status = requireEnum(root, "status", SUPPORTED_STATUS);
      int priority = root.path("priority").asInt(-1);
      if (priority < 0) {
        throw new IllegalArgumentException(
            "governance rule priority must be greater than or equal to 0");
      }
      validateRuleSpecificFields(ruleType, root);
      return new ValidatedGovernanceRule(
          objectMapper.writeValueAsString(root), ruleType, targetService, status, priority);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("governance rule content must be valid JSON", ex);
    }
  }

  private void validateRuleSpecificFields(String ruleType, JsonNode root) {
    switch (ruleType) {
      case "ROUTE" -> requireNode(root, "routes");
      case "RATE_LIMIT" -> requireNode(root, "limit");
      case "CIRCUIT_BREAKER" -> requireNode(root, "breaker");
      case "DEGRADE" -> requireNode(root, "degrade");
      default -> throw new IllegalArgumentException("unsupported governance ruleType: " + ruleType);
    }
  }

  private String requireEnum(JsonNode root, String fieldName, Set<String> allowedValues) {
    String value = requireText(root, fieldName).toUpperCase();
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException(fieldName + " is not supported: " + value);
    }
    return value;
  }

  private String requireText(JsonNode root, String fieldName) {
    String value = root.path(fieldName).asText("");
    if (value.isBlank()) {
      throw new IllegalArgumentException("governance rule " + fieldName + " must not be blank");
    }
    return value;
  }

  private void requireNode(JsonNode root, String fieldName) {
    if (root.path(fieldName).isMissingNode() || root.path(fieldName).isNull()) {
      throw new IllegalArgumentException("governance rule " + fieldName + " must be provided");
    }
  }

  public record ValidatedGovernanceRule(
      String normalizedContent,
      String ruleType,
      String targetService,
      String status,
      int priority) {

    public GovernanceRuleIndex toIndex(
        String configId,
        long scopeId,
        long releaseId,
        long revision,
        String ownerType,
        String ownerId,
        String env,
        String region,
        String zone,
        String cluster) {
      return new GovernanceRuleIndex(
          configId,
          scopeId,
          releaseId,
          revision,
          ownerType,
          ownerId,
          env,
          region,
          zone,
          cluster,
          ruleType,
          targetService,
          status,
          priority);
    }
  }
}

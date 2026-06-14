package io.github.stellnula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FeatureFlagValidator {

  private static final Pattern FLAG_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
  private static final Set<String> SUPPORTED_TYPES =
      Set.of("BOOLEAN", "VARIANT", "STRING", "NUMBER");
  private static final Set<String> SUPPORTED_ROLLOUT_TYPES = Set.of("PERCENTAGE");
  private static final Set<String> STABLE_BUCKET_FIELDS = Set.of("clientId", "clientIp");

  private final ObjectMapper objectMapper;

  public FeatureFlagValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** 校验 Feature Flag 内容并输出标准 JSON。 */
  public String validateAndNormalize(String content, String expectedKey) {
    try {
      JsonNode root = objectMapper.readTree(content);
      validateRoot(root, expectedKey);
      return objectMapper.writeValueAsString(root);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("feature flag content must be valid JSON", ex);
    }
  }

  /** 提取 Feature Flag 内容中的展示字段。 */
  public FeatureFlagDocument read(String content) {
    try {
      JsonNode root = objectMapper.readTree(content);
      validateRoot(root, root.path("key").asText(""));
      return new FeatureFlagDocument(
          root.path("key").asText(),
          root.path("type").asText("").toUpperCase(Locale.ROOT),
          root.path("enabled").asBoolean(false),
          root.path("defaultValue"),
          root.path("rules").isArray()
              ? (ArrayNode) root.path("rules")
              : objectMapper.createArrayNode(),
          root.path("variants").isArray()
              ? (ArrayNode) root.path("variants")
              : objectMapper.createArrayNode(),
          root.path("rollout").isObject() ? root.path("rollout") : null);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("feature flag content must be valid JSON", ex);
    }
  }

  /** 校验 Feature Flag key。 */
  public String normalizeKey(String key) {
    String resolved = requireText(key, "feature flag key");
    if (!FLAG_KEY_PATTERN.matcher(resolved).matches()) {
      throw new IllegalArgumentException(
          "feature flag key only supports letters, digits, dot, dash and underscore");
    }
    return resolved;
  }

  private void validateRoot(JsonNode root, String expectedKey) {
    if (!root.isObject()) {
      throw new IllegalArgumentException("feature flag content must be a JSON object");
    }
    String key = normalizeKey(root.path("key").asText(""));
    String resolvedExpectedKey = normalizeKey(expectedKey);
    if (!key.equals(resolvedExpectedKey)) {
      throw new IllegalArgumentException("feature flag key must match request key");
    }
    String type = requireEnum(root, "type", SUPPORTED_TYPES);
    requireNode(root, "defaultValue");
    validateValueType(type, root.path("defaultValue"), "defaultValue");
    validateRules(type, root.path("rules"));
    validateVariants(type, root.path("variants"));
    validateRollout(root.path("rollout"));
  }

  private void validateRules(String flagType, JsonNode rules) {
    if (rules.isMissingNode() || rules.isNull()) {
      return;
    }
    if (!rules.isArray()) {
      throw new IllegalArgumentException("feature flag rules must be an array");
    }
    for (JsonNode rule : rules) {
      if (!rule.isObject()) {
        throw new IllegalArgumentException("feature flag rule must be an object");
      }
      if (rule.has("value")) {
        validateValueType(flagType, rule.path("value"), "rule.value");
      }
      JsonNode conditions = rule.path("conditions");
      if (!conditions.isMissingNode() && !conditions.isNull()) {
        validateConditions(conditions);
      }
    }
  }

  private void validateConditions(JsonNode conditions) {
    if (!conditions.isArray()) {
      throw new IllegalArgumentException("feature flag rule conditions must be an array");
    }
    for (JsonNode condition : conditions) {
      if (!condition.isObject()) {
        throw new IllegalArgumentException("feature flag condition must be an object");
      }
      requireText(condition.path("attribute").asText(""), "feature flag condition attribute");
      requireText(condition.path("op").asText(""), "feature flag condition op");
    }
  }

  private void validateVariants(String flagType, JsonNode variants) {
    if (!"VARIANT".equals(flagType)) {
      return;
    }
    if (!variants.isArray() || variants.isEmpty()) {
      throw new IllegalArgumentException("feature flag variants must be provided for VARIANT type");
    }
    Set<String> keys = new HashSet<>();
    int weightSum = 0;
    for (JsonNode variant : variants) {
      String key = normalizeKey(variant.path("key").asText(""));
      if (!keys.add(key)) {
        throw new IllegalArgumentException("feature flag variant key must be unique: " + key);
      }
      int weight = variant.path("weight").asInt(-1);
      if (weight < 0 || weight > 100) {
        throw new IllegalArgumentException("feature flag variant weight must be between 0 and 100");
      }
      weightSum += weight;
    }
    if (weightSum > 100) {
      throw new IllegalArgumentException("feature flag variant weight sum must not exceed 100");
    }
  }

  private void validateRollout(JsonNode rollout) {
    if (rollout.isMissingNode() || rollout.isNull()) {
      return;
    }
    if (!rollout.isObject()) {
      throw new IllegalArgumentException("feature flag rollout must be an object");
    }
    requireEnum(rollout, "type", SUPPORTED_ROLLOUT_TYPES);
    String bucketBy =
        requireText(rollout.path("bucketBy").asText(""), "feature flag rollout bucketBy");
    if (!STABLE_BUCKET_FIELDS.contains(bucketBy) && !bucketBy.startsWith("labels.")) {
      throw new IllegalArgumentException(
          "feature flag rollout bucketBy must be clientId, clientIp or labels.xxx");
    }
  }

  private void validateValueType(String flagType, JsonNode value, String fieldName) {
    switch (flagType) {
      case "BOOLEAN" -> {
        if (!value.isBoolean()) {
          throw new IllegalArgumentException("feature flag " + fieldName + " must be boolean");
        }
      }
      case "VARIANT", "STRING" -> {
        if (!value.isTextual()) {
          throw new IllegalArgumentException("feature flag " + fieldName + " must be string");
        }
      }
      case "NUMBER" -> {
        if (!value.isNumber()) {
          throw new IllegalArgumentException("feature flag " + fieldName + " must be number");
        }
      }
      default ->
          throw new IllegalArgumentException("feature flag type is not supported: " + flagType);
    }
  }

  private String requireEnum(JsonNode root, String fieldName, Set<String> allowedValues) {
    String value =
        requireText(root.path(fieldName).asText(""), "feature flag " + fieldName)
            .toUpperCase(Locale.ROOT);
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException(
          "feature flag " + fieldName + " is not supported: " + value);
    }
    return value;
  }

  private void requireNode(JsonNode root, String fieldName) {
    if (root.path(fieldName).isMissingNode() || root.path(fieldName).isNull()) {
      throw new IllegalArgumentException("feature flag " + fieldName + " must be provided");
    }
  }

  private String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }

  public record FeatureFlagDocument(
      String key,
      String type,
      boolean enabled,
      JsonNode defaultValue,
      ArrayNode rules,
      ArrayNode variants,
      JsonNode rollout) {}
}

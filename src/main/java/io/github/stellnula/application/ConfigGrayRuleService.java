package io.github.stellnula.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.domain.ConfigGrayImpactClient;
import io.github.stellnula.domain.ConfigGrayMutationCommand;
import io.github.stellnula.domain.ConfigGrayMutationResult;
import io.github.stellnula.domain.ConfigGrayRecord;
import io.github.stellnula.domain.ConfigGrayRuleExpiry;
import io.github.stellnula.repository.ConfigGrayRuleRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfigGrayRuleService {

  private static final String DEFAULT_SCOPE = "default";

  private final ConfigGrayRuleRepository repository;
  private final ObjectMapper objectMapper;
  private final GrayRuleMatcher grayRuleMatcher;

  /** 创建、更新或发布灰度规则。 */
  public ConfigGrayMutationResult upsert(ConfigGrayMutationCommand command) {
    ConfigGrayMutationCommand normalized = normalize(command, null);
    validateJson(normalized.grayRules());
    return repository.mutate(normalized);
  }

  /** 结束灰度规则，让客户端回到当前基线配置或删除状态。 */
  public ConfigGrayMutationResult end(ConfigGrayMutationCommand command) {
    ConfigGrayMutationCommand normalized = normalize(command, "ENDED");
    validateJson(normalized.grayRules());
    return repository.mutate(normalized);
  }

  /** 查询灰度规则最新记录。 */
  public Optional<ConfigGrayRecord> findLatest(
      String configId, String grayName, String env, String region, String zone, String cluster) {
    return repository.findLatest(
        requireText(configId, "configId"),
        requireText(grayName, "grayName"),
        requireText(env, "env"),
        defaultValue(region),
        defaultValue(zone),
        defaultValue(cluster));
  }

  /** 自动结束已经超过 endTime 的 ACTIVE 灰度规则。 */
  public int endExpiredRules(int limit) {
    OffsetDateTime now = OffsetDateTime.now();
    List<ConfigGrayRuleExpiry> expiredRules = repository.findExpiredActiveRules(now, limit);
    int endedCount = 0;
    for (ConfigGrayRuleExpiry expiredRule : expiredRules) {
      try {
        end(
            new ConfigGrayMutationCommand(
                expiredRule.configId(),
                expiredRule.grayName(),
                expiredRule.env(),
                expiredRule.region(),
                expiredRule.zone(),
                expiredRule.cluster(),
                expiredRule.ruleType(),
                expiredRule.grayRules(),
                expiredRule.configValue(),
                expiredRule.priority(),
                "ENDED",
                expiredRule.startTime(),
                now,
                "system",
                "gray rule expired"));
        endedCount++;
      } catch (RuntimeException ignored) {
        // Ignore single-rule race and continue ending the remaining expired rules.
      }
    }
    return endedCount;
  }

  /** 查询当前灰度规则实际命中的客户端实例。 */
  public List<ConfigGrayImpactClient> findImpactClients(
      String configId,
      String grayName,
      String env,
      String region,
      String zone,
      String cluster,
      int limit) {
    ConfigGrayRecord record =
        findLatest(configId, grayName, env, region, zone, cluster)
            .orElseThrow(() -> new IllegalArgumentException("gray rule does not exist"));
    if (!grayRuleMatcher.isEffective(
        record.status(), record.startTime(), record.endTime(), OffsetDateTime.now())) {
      return List.of();
    }
    return repository
        .findImpactCandidates(
            requireText(configId, "configId"),
            requireText(grayName, "grayName"),
            requireText(env, "env"),
            defaultValue(region),
            defaultValue(zone),
            defaultValue(cluster),
            Math.max(1, limit))
        .stream()
        .filter(
            client -> grayRuleMatcher.matchesRules(client.toClientContext(), record.grayRules()))
        .toList();
  }

  private ConfigGrayMutationCommand normalize(
      ConfigGrayMutationCommand command, String forcedStatus) {
    String status = forcedStatus == null ? defaultText(command.status(), "ACTIVE") : forcedStatus;
    return new ConfigGrayMutationCommand(
        requireText(command.configId(), "configId"),
        requireText(command.grayName(), "grayName"),
        requireText(command.env(), "env"),
        defaultValue(command.region()),
        defaultValue(command.zone()),
        defaultValue(command.cluster()),
        requireEnum(command.ruleType(), "ruleType", "IP", "TAG", "PERCENTAGE", "COMPOSITE"),
        requireText(command.grayRules(), "grayRules"),
        defaultText(command.configValue(), ""),
        command.priority() <= 0 ? 100 : command.priority(),
        requireEnum(status, "status", "DRAFT", "ACTIVE", "ENDED"),
        command.startTime(),
        command.endTime(),
        defaultText(command.operator(), "system"),
        defaultText(command.reason(), "gray rule " + status.toLowerCase()));
  }

  private void validateJson(String value) {
    try {
      objectMapper.readTree(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("grayRules must be valid JSON", ex);
    }
  }

  private String requireEnum(String value, String fieldName, String... allowedValues) {
    String resolved = requireText(value, fieldName).toUpperCase();
    for (String allowedValue : allowedValues) {
      if (allowedValue.equals(resolved)) {
        return resolved;
      }
    }
    throw new IllegalArgumentException(fieldName + " is not supported: " + value);
  }

  private String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  private String defaultValue(String value) {
    return defaultText(value, DEFAULT_SCOPE);
  }

  private String defaultText(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}

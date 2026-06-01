package io.github.stellnula.application;

import io.github.stellnula.domain.ConfigMutationAction;
import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ConfigMutationResult;
import io.github.stellnula.domain.ConfigRecord;
import io.github.stellnula.repository.ConfigMutationRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfigMutationService {

  private static final String DEFAULT_SCOPE = "default";

  private final ConfigMutationRepository repository;
  private final io.github.stellnula.config.DataPlaneProperties properties;

  /** 新增或更新配置，写入发布版本和全局 revision。 */
  public ConfigMutationResult upsert(ConfigMutationCommand command) {
    return repository.mutate(normalize(command, ConfigMutationAction.UPSERT));
  }

  /** 删除配置，写入删除版本和全局 revision。 */
  public ConfigMutationResult delete(ConfigMutationCommand command) {
    return repository.mutate(normalize(command, ConfigMutationAction.DELETE));
  }

  /** 查询指定配置在指定作用域下的最新记录。 */
  public Optional<ConfigRecord> findLatest(
      String configId, String env, String region, String zone, String cluster) {
    return repository.findLatest(
        configId,
        requireText(env, "env"),
        defaultValue(region),
        defaultValue(zone),
        defaultValue(cluster));
  }

  /** 按服务治理维度查询最新规则。 */
  public List<ConfigRecord> findGovernanceRules(
      String env, String ownerId, String ruleType, String targetService, String status) {
    return repository.findGovernanceRules(
        requireText(env, "env"), ownerId, ruleType, targetService, status);
  }

  private ConfigMutationCommand normalize(
      ConfigMutationCommand command, ConfigMutationAction action) {
    return new ConfigMutationCommand(
        action,
        requireText(command.configId(), "configId"),
        defaultText(command.configName(), command.configId()),
        requireEnum(
            defaultText(command.ownerType(), "APPLICATION"), "ownerType", "APPLICATION", "PUBLIC"),
        requireText(command.ownerId(), "ownerId"),
        defaultValue(command.namespaceCode()),
        defaultValue(command.groupCode()),
        requireEnum(defaultText(command.contentType(), "KV"), "contentType", "KV", "FILE"),
        command.sensitive(),
        command.description(),
        requireText(command.env(), "env"),
        defaultValue(command.region()),
        defaultValue(command.zone()),
        defaultValue(command.cluster()),
        requireEnum(
            defaultText(command.scopeMode(), "INHERITABLE"),
            "scopeMode",
            "EXACT",
            "INHERITABLE",
            "REPLICATED"),
        validateContentSize(
            action == ConfigMutationAction.DELETE ? "" : defaultText(command.content(), "")),
        defaultText(command.operator(), "system"),
        defaultText(command.reason(), action.name()));
  }

  private String validateContentSize(String content) {
    if (content.getBytes(StandardCharsets.UTF_8).length > properties.maxConfigContentBytes()) {
      throw new IllegalArgumentException("config content exceeds max allowed bytes");
    }
    return content;
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

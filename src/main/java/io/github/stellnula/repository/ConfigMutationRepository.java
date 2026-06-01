package io.github.stellnula.repository;

import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ConfigMutationResult;
import io.github.stellnula.domain.ConfigRecord;
import java.util.List;
import java.util.Optional;

public interface ConfigMutationRepository {

  /** 发布或删除配置，并写入 revision、change event、history 和 audit。 */
  ConfigMutationResult mutate(ConfigMutationCommand command);

  /** 查询指定配置和作用域下的最新版本记录。 */
  Optional<ConfigRecord> findLatest(
      String configId, String env, String region, String zone, String cluster);

  /** 按服务治理规则维度查询最新规则。 */
  List<ConfigRecord> findGovernanceRules(
      String env, String ownerId, String ruleType, String targetService, String status);
}

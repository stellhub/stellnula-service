package io.github.stellnula.repository;

import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ConfigMutationResult;
import io.github.stellnula.domain.ConfigRecord;
import io.github.stellnula.domain.ControlPlaneAppConfigRecord;
import java.util.List;
import java.util.Optional;

public interface ConfigMutationRepository {

  /** 发布或删除配置，并写入 revision、change event、history 和 audit。 */
  ConfigMutationResult mutate(ConfigMutationCommand command);

  /** 保存控制面配置草稿，不进入客户端可见发布流。 */
  ConfigMutationResult saveDraft(ConfigMutationCommand command);

  /** 查询指定配置和作用域下的最新版本记录。 */
  Optional<ConfigRecord> findLatest(
      String configId, String env, String region, String zone, String cluster);

  /** 查询控制面配置列表。 */
  List<ControlPlaneAppConfigRecord> findControlPlaneConfigs(
      String ownerType,
      String ownerId,
      String namespaceCode,
      String env,
      String cluster,
      String group);

  /** 查询控制面配置详情。 */
  Optional<ControlPlaneAppConfigRecord> findControlPlaneConfig(
      String ownerType, String ownerId, String namespaceCode, String configId);

  /** 按服务治理规则维度查询最新规则。 */
  List<ConfigRecord> findGovernanceRules(
      String env, String ownerId, String ruleType, String targetService, String status);
}

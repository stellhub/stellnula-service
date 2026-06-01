package io.github.stellnula.repository;

import io.github.stellnula.domain.ConfigGrayImpactClient;
import io.github.stellnula.domain.ConfigGrayMutationCommand;
import io.github.stellnula.domain.ConfigGrayMutationResult;
import io.github.stellnula.domain.ConfigGrayRecord;
import io.github.stellnula.domain.ConfigGrayRuleExpiry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ConfigGrayRuleRepository {

  /** 新增、更新、发布或结束灰度规则。 */
  ConfigGrayMutationResult mutate(ConfigGrayMutationCommand command);

  /** 查询指定灰度规则。 */
  Optional<ConfigGrayRecord> findLatest(
      String configId, String grayName, String env, String region, String zone, String cluster);

  /** 查询已经过期但仍处于 ACTIVE 的灰度规则。 */
  List<ConfigGrayRuleExpiry> findExpiredActiveRules(OffsetDateTime now, int limit);

  /** 查询灰度规则潜在影响客户端。 */
  List<ConfigGrayImpactClient> findImpactCandidates(
      String configId,
      String grayName,
      String env,
      String region,
      String zone,
      String cluster,
      int limit);
}

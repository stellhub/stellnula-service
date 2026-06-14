package io.github.stellnula.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.application.SensitiveConfigCodec;
import io.github.stellnula.domain.ConfigGrayImpactClient;
import io.github.stellnula.domain.ConfigGrayMutationCommand;
import io.github.stellnula.domain.ConfigGrayMutationResult;
import io.github.stellnula.domain.ConfigGrayRecord;
import io.github.stellnula.domain.ConfigGrayRuleExpiry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JdbcConfigGrayRuleRepository implements ConfigGrayRuleRepository {

  private static final String FIND_SCOPE_ID_SQL =
      """
      select s.id
        from config_definition d
        join config_scope s
          on s.config_id = d.config_id
       where d.config_id = ?
         and s.env = ?
         and s.region = ?
         and s.zone = ?
         and s.cluster = ?
         and d.deleted = false
       limit 1
      """;

  private static final String FIND_CONFIG_SENSITIVE_SQL =
      """
      select sensitive
        from config_definition
       where config_id = ?
         and deleted = false
      """;

  private static final String NEXT_GRAY_VERSION_SQL =
      """
      select coalesce(max(gray_version), 0) + 1
        from config_gray_rule
       where config_id = ?
         and scope_id = ?
         and gray_name = ?
      """;

  private static final String INSERT_REVISION_SQL =
      """
      insert into config_revision (
          revision_type,
          config_id,
          scope_id,
          source_table,
          event_type,
          revision_reason,
          payload,
          created_by
      ) values ('GRAY_ROUTE', ?, ?, 'config_gray_rule', ?, ?, ?::jsonb, ?)
      returning revision
      """;

  private static final String UPSERT_GRAY_RULE_SQL =
      """
      insert into config_gray_rule (
          config_id,
          scope_id,
          gray_name,
          rule_type,
          gray_rules,
          config_value,
          gray_version,
          effective_revision,
          checksum,
          priority,
          status,
          start_time,
          end_time,
          created_by,
          updated_by,
          updated_at
      ) values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
      on conflict (config_id, scope_id, gray_name)
      do update set
          rule_type = excluded.rule_type,
          gray_rules = excluded.gray_rules,
          config_value = excluded.config_value,
          gray_version = excluded.gray_version,
          effective_revision = excluded.effective_revision,
          checksum = excluded.checksum,
          priority = excluded.priority,
          status = excluded.status,
          start_time = excluded.start_time,
          end_time = excluded.end_time,
          updated_by = excluded.updated_by,
          updated_at = now()
      returning id, updated_at
      """;

  private static final String UPDATE_REVISION_SOURCE_SQL =
      """
      update config_revision
         set source_id = ?
       where revision = ?
      """;

  private static final String INSERT_CHANGE_EVENT_SQL =
      """
      insert into change_event (
          revision,
          config_id,
          scope_id,
          env,
          event_type,
          payload
      ) values (?, ?, ?, ?, ?, ?::jsonb)
      """;

  private static final String INSERT_HISTORY_SQL =
      """
      insert into config_release_history (
          config_id,
          scope_id,
          release_type,
          gray_rule_id,
          before_value,
          after_value,
          before_revision,
          after_revision,
          operator,
          operation_reason
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String FIND_GRAY_RULE_SQL =
      """
      select g.id,
             g.config_id,
             g.scope_id,
             g.gray_name,
             g.rule_type,
             g.gray_rules::text as gray_rules,
             g.config_value,
             d.sensitive,
             g.gray_version,
             g.effective_revision,
             g.checksum,
             g.priority,
             g.status,
             g.start_time,
             g.end_time,
             g.updated_at
        from config_gray_rule g
        join config_definition d
          on d.config_id = g.config_id
        join config_scope s
          on s.id = g.scope_id
       where g.config_id = ?
         and g.gray_name = ?
         and s.env = ?
         and s.region = ?
         and s.zone = ?
         and s.cluster = ?
       order by g.gray_version desc
      limit 1
      """;

  private static final String FIND_EXPIRED_ACTIVE_RULES_SQL =
      """
      select g.config_id,
             g.gray_name,
             s.env,
             s.region,
             s.zone,
             s.cluster,
             g.rule_type,
             g.gray_rules::text as gray_rules,
             g.config_value,
             d.sensitive,
             g.priority,
             g.start_time,
             g.end_time
        from config_gray_rule g
        join config_definition d
          on d.config_id = g.config_id
        join config_scope s
          on s.id = g.scope_id
       where g.status = 'ACTIVE'
         and g.end_time is not null
         and g.end_time <= ?
         and d.deleted = false
       order by g.end_time, g.id
       limit ?
      """;

  private static final String FIND_IMPACT_CANDIDATES_SQL =
      """
      select c.app_id,
             c.client_id,
             c.env,
             c.region,
             c.zone,
             c.cluster,
             c.namespace_code,
             c.group_code,
             c.client_ip,
             c.host_name,
             c.sdk_version,
             c.labels::text as labels,
             c.last_seen_at
        from config_gray_rule g
        join config_definition d
          on d.config_id = g.config_id
        join config_scope s
          on s.id = g.scope_id
        join client_instance c
          on c.env = s.env
         and c.namespace_code = d.namespace_code
         and c.group_code = d.group_code
         and (d.owner_type = 'PUBLIC' or c.app_id = d.owner_id)
         and (
             s.scope_mode <> 'EXACT'
             or (c.region = s.region and c.zone = s.zone and c.cluster = s.cluster)
         )
         and (
             s.scope_mode = 'EXACT'
             or (
                 (s.region = 'default' or c.region = s.region)
                 and (s.zone = 'default' or c.zone = s.zone)
                 and (s.cluster = 'default' or c.cluster = s.cluster)
             )
         )
       where g.config_id = ?
         and g.gray_name = ?
         and s.env = ?
         and s.region = ?
         and s.zone = ?
         and s.cluster = ?
       order by c.last_seen_at desc
       limit ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final SensitiveConfigCodec sensitiveConfigCodec;

  @Override
  @Transactional
  public ConfigGrayMutationResult mutate(ConfigGrayMutationCommand command) {
    long scopeId = findScopeId(command);
    boolean sensitive = findConfigSensitive(command.configId());
    Optional<ConfigGrayRecord> before =
        findLatest(
            command.configId(),
            command.grayName(),
            command.env(),
            command.region(),
            command.zone(),
            command.cluster());
    if ("ENDED".equals(command.status()) && before.isEmpty()) {
      throw new IllegalArgumentException("gray rule does not exist");
    }
    ConfigGrayRecord beforeRecord = before.orElse(null);
    String ruleType = resolveRuleType(command, beforeRecord);
    String grayRules = resolveGrayRules(command, beforeRecord);
    String configValue = resolveConfigValue(command, beforeRecord);
    int priority = resolvePriority(command, beforeRecord);
    OffsetDateTime startTime = resolveStartTime(command, beforeRecord);
    OffsetDateTime endTime = resolveEndTime(command);
    long grayVersion = nextGrayVersion(command.configId(), scopeId, command.grayName());
    String checksum = checksum(configValue);
    String storedConfigValue = sensitiveConfigCodec.encryptIfSensitive(sensitive, configValue);
    String eventType = eventType(command.status());
    long revision =
        insertRevision(
            command,
            scopeId,
            eventType,
            before.map(ConfigGrayRecord::effectiveRevision).orElse(null));
    GrayRuleUpsertResult upsertResult =
        jdbcTemplate.queryForObject(
            UPSERT_GRAY_RULE_SQL,
            this::mapUpsertResult,
            command.configId(),
            scopeId,
            command.grayName(),
            ruleType,
            grayRules,
            storedConfigValue,
            grayVersion,
            revision,
            checksum,
            priority,
            command.status(),
            toTimestamp(startTime),
            toTimestamp(endTime),
            command.operator(),
            command.operator());
    long grayRuleId = upsertResult == null ? 0 : upsertResult.id();
    OffsetDateTime updatedAt =
        upsertResult == null ? OffsetDateTime.now() : upsertResult.updatedAt();
    jdbcTemplate.update(UPDATE_REVISION_SOURCE_SQL, grayRuleId, revision);
    insertChangeEvent(command, scopeId, revision, eventType, checksum);
    insertHistory(command, scopeId, grayRuleId, revision, beforeRecord, configValue, sensitive);
    return new ConfigGrayMutationResult(
        grayRuleId,
        command.configId(),
        scopeId,
        command.grayName(),
        grayVersion,
        revision,
        command.status(),
        checksum,
        updatedAt);
  }

  private boolean findConfigSensitive(String configId) {
    try {
      Boolean sensitive =
          jdbcTemplate.queryForObject(FIND_CONFIG_SENSITIVE_SQL, Boolean.class, configId);
      return Boolean.TRUE.equals(sensitive);
    } catch (EmptyResultDataAccessException ex) {
      throw new IllegalArgumentException("config definition does not exist");
    }
  }

  @Override
  public Optional<ConfigGrayRecord> findLatest(
      String configId, String grayName, String env, String region, String zone, String cluster) {
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(
              FIND_GRAY_RULE_SQL, this::mapRecord, configId, grayName, env, region, zone, cluster));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  @Override
  public List<ConfigGrayRuleExpiry> findExpiredActiveRules(OffsetDateTime now, int limit) {
    return jdbcTemplate.query(
        FIND_EXPIRED_ACTIVE_RULES_SQL, this::mapExpiredRule, toTimestamp(now), limit);
  }

  @Override
  public List<ConfigGrayImpactClient> findImpactCandidates(
      String configId,
      String grayName,
      String env,
      String region,
      String zone,
      String cluster,
      int limit) {
    return jdbcTemplate.query(
        FIND_IMPACT_CANDIDATES_SQL,
        this::mapImpactClient,
        configId,
        grayName,
        env,
        region,
        zone,
        cluster,
        limit);
  }

  private long findScopeId(ConfigGrayMutationCommand command) {
    try {
      Long scopeId =
          jdbcTemplate.queryForObject(
              FIND_SCOPE_ID_SQL,
              Long.class,
              command.configId(),
              command.env(),
              command.region(),
              command.zone(),
              command.cluster());
      return scopeId == null ? 0 : scopeId;
    } catch (EmptyResultDataAccessException ex) {
      throw new IllegalArgumentException("config scope does not exist");
    }
  }

  private long nextGrayVersion(String configId, long scopeId, String grayName) {
    Long version =
        jdbcTemplate.queryForObject(NEXT_GRAY_VERSION_SQL, Long.class, configId, scopeId, grayName);
    return version == null ? 1 : version;
  }

  private long insertRevision(
      ConfigGrayMutationCommand command, long scopeId, String eventType, Long beforeRevision) {
    Long revision =
        jdbcTemplate.queryForObject(
            INSERT_REVISION_SQL,
            Long.class,
            command.configId(),
            scopeId,
            eventType,
            command.reason(),
            writeJson(
                Map.of(
                    "grayName",
                    command.grayName(),
                    "beforeRevision",
                    beforeRevision == null ? 0 : beforeRevision)),
            command.operator());
    return revision == null ? 0 : revision;
  }

  private void insertChangeEvent(
      ConfigGrayMutationCommand command,
      long scopeId,
      long revision,
      String eventType,
      String checksum) {
    jdbcTemplate.update(
        INSERT_CHANGE_EVENT_SQL,
        revision,
        command.configId(),
        scopeId,
        command.env(),
        eventType,
        writeJson(Map.of("grayName", command.grayName(), "checksum", checksum)));
  }

  private void insertHistory(
      ConfigGrayMutationCommand command,
      long scopeId,
      long grayRuleId,
      long revision,
      ConfigGrayRecord before,
      String afterValue,
      boolean sensitive) {
    jdbcTemplate.update(
        INSERT_HISTORY_SQL,
        command.configId(),
        scopeId,
        historyType(command.status(), before == null),
        grayRuleId,
        before == null
            ? null
            : sensitiveConfigCodec.maskIfSensitive(sensitive, before.configValue()),
        sensitiveConfigCodec.maskIfSensitive(sensitive, afterValue),
        before == null ? null : before.effectiveRevision(),
        revision,
        command.operator(),
        command.reason());
  }

  private String eventType(String status) {
    return switch (status) {
      case "DRAFT" -> "GRAY_CREATED";
      case "ENDED" -> "GRAY_ENDED";
      default -> "GRAY_PUBLISHED";
    };
  }

  private String historyType(String status, boolean created) {
    if (created) {
      return "GRAY_CREATE";
    }
    return "ENDED".equals(status) ? "GRAY_END" : "GRAY_RULE_UPDATE";
  }

  private String resolveRuleType(ConfigGrayMutationCommand command, ConfigGrayRecord before) {
    return "ENDED".equals(command.status()) && before != null
        ? before.ruleType()
        : command.ruleType();
  }

  private String resolveGrayRules(ConfigGrayMutationCommand command, ConfigGrayRecord before) {
    return "ENDED".equals(command.status()) && before != null
        ? before.grayRules()
        : command.grayRules();
  }

  private String resolveConfigValue(ConfigGrayMutationCommand command, ConfigGrayRecord before) {
    return "ENDED".equals(command.status()) && before != null
        ? before.configValue()
        : command.configValue();
  }

  private int resolvePriority(ConfigGrayMutationCommand command, ConfigGrayRecord before) {
    return "ENDED".equals(command.status()) && before != null
        ? before.priority()
        : command.priority();
  }

  private OffsetDateTime resolveStartTime(
      ConfigGrayMutationCommand command, ConfigGrayRecord before) {
    return "ENDED".equals(command.status()) && before != null
        ? before.startTime()
        : command.startTime();
  }

  private OffsetDateTime resolveEndTime(ConfigGrayMutationCommand command) {
    if (!"ENDED".equals(command.status())) {
      return command.endTime();
    }
    return command.endTime() == null ? OffsetDateTime.now() : command.endTime();
  }

  private String checksum(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:"
          + HexFormatHolder.HEX.formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to serialize JSON value", ex);
    }
  }

  private GrayRuleUpsertResult mapUpsertResult(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new GrayRuleUpsertResult(
        resultSet.getLong("id"), toOffsetDateTime(resultSet.getTimestamp("updated_at")));
  }

  private ConfigGrayRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ConfigGrayRecord(
        resultSet.getLong("id"),
        resultSet.getString("config_id"),
        resultSet.getLong("scope_id"),
        resultSet.getString("gray_name"),
        resultSet.getString("rule_type"),
        resultSet.getString("gray_rules"),
        sensitiveConfigCodec.decryptIfSensitive(
            resultSet.getBoolean("sensitive"), resultSet.getString("config_value")),
        resultSet.getLong("gray_version"),
        resultSet.getLong("effective_revision"),
        resultSet.getString("checksum"),
        resultSet.getInt("priority"),
        resultSet.getString("status"),
        toOffsetDateTime(resultSet.getTimestamp("start_time")),
        toOffsetDateTime(resultSet.getTimestamp("end_time")),
        toOffsetDateTime(resultSet.getTimestamp("updated_at")));
  }

  private ConfigGrayRuleExpiry mapExpiredRule(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ConfigGrayRuleExpiry(
        resultSet.getString("config_id"),
        resultSet.getString("gray_name"),
        resultSet.getString("env"),
        resultSet.getString("region"),
        resultSet.getString("zone"),
        resultSet.getString("cluster"),
        resultSet.getString("rule_type"),
        resultSet.getString("gray_rules"),
        sensitiveConfigCodec.decryptIfSensitive(
            resultSet.getBoolean("sensitive"), resultSet.getString("config_value")),
        resultSet.getInt("priority"),
        toOffsetDateTime(resultSet.getTimestamp("start_time")),
        toOffsetDateTime(resultSet.getTimestamp("end_time")));
  }

  private ConfigGrayImpactClient mapImpactClient(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ConfigGrayImpactClient(
        resultSet.getString("app_id"),
        resultSet.getString("client_id"),
        resultSet.getString("env"),
        resultSet.getString("region"),
        resultSet.getString("zone"),
        resultSet.getString("cluster"),
        resultSet.getString("namespace_code"),
        resultSet.getString("group_code"),
        resultSet.getString("client_ip"),
        resultSet.getString("host_name"),
        resultSet.getString("sdk_version"),
        readStringMap(resultSet.getString("labels")),
        toOffsetDateTime(resultSet.getTimestamp("last_seen_at")));
  }

  private Map<String, String> readStringMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to parse JSON value", ex);
    }
  }

  private Timestamp toTimestamp(OffsetDateTime time) {
    return time == null ? null : Timestamp.from(time.toInstant());
  }

  private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
    return timestamp == null
        ? null
        : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
  }

  private record GrayRuleUpsertResult(long id, OffsetDateTime updatedAt) {}

  private static class HexFormatHolder {
    private static final java.util.HexFormat HEX = java.util.HexFormat.of();
  }
}

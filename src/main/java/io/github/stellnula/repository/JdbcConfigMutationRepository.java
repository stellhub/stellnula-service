package io.github.stellnula.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.application.SensitiveConfigCodec;
import io.github.stellnula.domain.ConfigMutationAction;
import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ConfigMutationResult;
import io.github.stellnula.domain.ConfigRecord;
import io.github.stellnula.domain.ControlPlaneAppConfigRecord;
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
public class JdbcConfigMutationRepository implements ConfigMutationRepository {

  private static final String GOVERNANCE_NAMESPACE = "governance";
  private static final String GOVERNANCE_GROUP = "service-governance";

  private static final String UPSERT_DEFINITION_SQL =
      """
      insert into config_definition (
          config_id,
          config_name,
          owner_type,
          owner_id,
          namespace_code,
          group_code,
          config_format,
          content_type,
          sensitive,
          description,
          deleted,
          updated_at
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, now())
      on conflict (config_id)
      do update set
          config_name = excluded.config_name,
          owner_type = excluded.owner_type,
          owner_id = excluded.owner_id,
          namespace_code = excluded.namespace_code,
          group_code = excluded.group_code,
          config_format = excluded.config_format,
          content_type = excluded.content_type,
          sensitive = excluded.sensitive,
          description = excluded.description,
          deleted = false,
          updated_at = now()
      """;

  private static final String UPSERT_SCOPE_SQL =
      """
      insert into config_scope (
          config_id,
          env,
          region,
          zone,
          cluster,
          scope_mode,
          updated_at
      ) values (?, ?, ?, ?, ?, ?, now())
      on conflict (config_id, env, region, zone, cluster)
      do update set
          scope_mode = excluded.scope_mode,
          updated_at = now()
      returning id
      """;

  private static final String NEXT_VERSION_SQL =
      """
      select coalesce(max(version), 0) + 1
        from config_release
       where config_id = ?
         and scope_id = ?
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
      ) values (?, ?, ?, 'config_release', ?, ?, ?::jsonb, ?)
      returning revision
      """;

  private static final String INSERT_RELEASE_SQL =
      """
      insert into config_release (
          release_no,
          config_id,
          scope_id,
          version,
          revision,
          content,
          checksum,
          release_status,
          release_title,
          release_reason,
          created_by
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      returning id, released_at
      """;

  private static final String INSERT_GOVERNANCE_RULE_INDEX_SQL =
      """
      insert into governance_rule_index (
          config_id,
          scope_id,
          release_id,
          revision,
          owner_type,
          owner_id,
          env,
          region,
          zone,
          cluster,
          rule_type,
          target_service,
          status,
          priority
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (release_id)
      do update set
          revision = excluded.revision,
          owner_type = excluded.owner_type,
          owner_id = excluded.owner_id,
          env = excluded.env,
          region = excluded.region,
          zone = excluded.zone,
          cluster = excluded.cluster,
          rule_type = excluded.rule_type,
          target_service = excluded.target_service,
          status = excluded.status,
          priority = excluded.priority,
          updated_at = now()
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
          before_value,
          after_value,
          before_revision,
          after_revision,
          operator,
          operation_reason
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String INSERT_AUDIT_SQL =
      """
      insert into config_release_audit (
          release_no,
          action,
          operator,
          before_value,
          after_value
      ) values (?, ?, ?, ?::jsonb, ?::jsonb)
      """;

  private static final String FIND_LATEST_SQL =
      """
      select d.config_id,
             d.config_name,
             d.owner_type,
             d.owner_id,
             d.namespace_code,
             d.group_code,
             d.config_format,
             d.content_type,
             d.sensitive,
             s.id as scope_id,
             s.env,
             s.region,
             s.zone,
             s.cluster,
             s.scope_mode,
             r.release_no,
             r.version,
             r.revision,
             r.content,
             r.checksum,
             r.release_status,
             r.released_at
        from config_definition d
        join config_scope s
          on s.config_id = d.config_id
        join config_release r
          on r.config_id = d.config_id
         and r.scope_id = s.id
       where d.config_id = ?
         and s.env = ?
         and s.region = ?
         and s.zone = ?
         and s.cluster = ?
       order by r.version desc
       limit 1
      """;

  private static final String FIND_GOVERNANCE_RULES_SQL =
      """
      with latest_release as (
          select distinct on (r.config_id, r.scope_id)
                 r.id as release_id,
                 r.config_id,
                 r.scope_id,
                 r.release_no,
                 r.version,
                 r.revision,
                 r.content,
                 r.checksum,
                 r.release_status,
                 r.released_at
            from config_release r
           order by r.config_id, r.scope_id, r.version desc
      )
      select d.config_id,
             d.config_name,
             d.owner_type,
             d.owner_id,
             d.namespace_code,
             d.group_code,
             d.config_format,
             d.content_type,
             d.sensitive,
             s.id as scope_id,
             s.env,
             s.region,
             s.zone,
             s.cluster,
             s.scope_mode,
             r.release_no,
             r.version,
             r.revision,
             r.content,
             r.checksum,
             r.release_status,
             r.released_at,
             gi.rule_type as governance_rule_type,
             gi.target_service as governance_target_service,
             gi.status as governance_status,
             gi.priority as governance_priority
        from latest_release r
        join governance_rule_index gi
          on gi.release_id = r.release_id
        join config_definition d
          on d.config_id = r.config_id
        join config_scope s
          on s.id = r.scope_id
       where d.namespace_code = 'governance'
         and d.group_code = 'service-governance'
         and r.release_status = 'PUBLISHED'
         and s.env = ?
         and (? = '' or d.owner_id = ?)
         and (? = '' or gi.rule_type = upper(?))
         and (? = '' or gi.target_service = ?)
         and (? = '' or gi.status = upper(?))
      order by gi.priority asc, d.config_id
      """;

  private static final String FIND_CONTROL_PLANE_CONFIGS_SQL =
      """
      with latest_release as (
          select distinct on (r.config_id, r.scope_id)
                 r.config_id,
                 r.scope_id,
                 r.version,
                 r.content,
                 r.release_status,
                 r.created_by,
                 r.released_at
            from config_release r
           order by r.config_id, r.scope_id, r.version desc
      ),
      latest_published as (
          select distinct on (r.config_id, r.scope_id)
                 r.config_id,
                 r.scope_id,
                 r.released_at
            from config_release r
           where r.release_status = 'PUBLISHED'
           order by r.config_id, r.scope_id, r.version desc
      )
      select d.config_id,
             d.config_name,
             d.owner_id,
             d.namespace_code,
             d.group_code,
             d.config_format,
             d.content_type,
             d.sensitive,
             d.description,
             s.env,
             s.cluster,
             r.version,
             r.content,
             r.release_status,
             r.created_by,
             r.released_at,
             p.released_at as published_at,
             case when p.config_id is null then false else true end as format_locked
        from latest_release r
        join config_definition d
          on d.config_id = r.config_id
        join config_scope s
          on s.id = r.scope_id
        left join latest_published p
          on p.config_id = r.config_id
         and p.scope_id = r.scope_id
       where d.owner_type = ?
          and d.owner_id = ?
          and d.namespace_code = ?
          and d.deleted = false
          and r.release_status <> 'DELETED'
          and (? = '' or s.env = ?)
          and (? = '' or s.cluster = ?)
          and (? = '' or d.group_code = ?)
        order by r.released_at desc, d.config_id
      """;

  private static final String FIND_CONTROL_PLANE_CONFIG_SQL =
      """
      with latest_release as (
          select distinct on (r.config_id, r.scope_id)
                 r.config_id,
                 r.scope_id,
                 r.version,
                 r.content,
                 r.release_status,
                 r.created_by,
                 r.released_at
            from config_release r
           order by r.config_id, r.scope_id, r.version desc
      ),
      latest_published as (
          select distinct on (r.config_id, r.scope_id)
                 r.config_id,
                 r.scope_id,
                 r.released_at
            from config_release r
           where r.release_status = 'PUBLISHED'
           order by r.config_id, r.scope_id, r.version desc
      )
      select d.config_id,
             d.config_name,
             d.owner_id,
             d.namespace_code,
             d.group_code,
             d.config_format,
             d.content_type,
             d.sensitive,
             d.description,
             s.env,
             s.cluster,
             r.version,
             r.content,
             r.release_status,
             r.created_by,
             r.released_at,
             p.released_at as published_at,
             case when p.config_id is null then false else true end as format_locked
        from latest_release r
        join config_definition d
          on d.config_id = r.config_id
        join config_scope s
          on s.id = r.scope_id
        left join latest_published p
          on p.config_id = r.config_id
         and p.scope_id = r.scope_id
       where d.owner_type = ?
         and d.owner_id = ?
         and d.namespace_code = ?
         and d.config_id = ?
         and d.deleted = false
         and r.release_status <> 'DELETED'
       order by r.released_at desc
       limit 1
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final SensitiveConfigCodec sensitiveConfigCodec;

  @Override
  @Transactional
  public ConfigMutationResult mutate(ConfigMutationCommand command) {
    return mutate(command, releaseStatus(command.action()), true);
  }

  @Override
  @Transactional
  public ConfigMutationResult saveDraft(ConfigMutationCommand command) {
    return mutate(command, "DRAFT", false);
  }

  private ConfigMutationResult mutate(
      ConfigMutationCommand command, String releaseStatus, boolean clientVisible) {
    jdbcTemplate.update(
        UPSERT_DEFINITION_SQL,
        command.configId(),
        command.configName(),
        command.ownerType(),
        command.ownerId(),
        command.namespaceCode(),
        command.groupCode(),
        command.format(),
        command.contentType(),
        command.sensitive(),
        command.description());
    Long scopeId =
        jdbcTemplate.queryForObject(
            UPSERT_SCOPE_SQL,
            Long.class,
            command.configId(),
            command.env(),
            command.region(),
            command.zone(),
            command.cluster(),
            command.scopeMode());
    long resolvedScopeId = scopeId == null ? 0 : scopeId;
    Optional<ConfigRecord> before =
        findLatest(
            command.configId(), command.env(), command.region(), command.zone(), command.cluster());
    long version = nextVersion(command.configId(), resolvedScopeId);
    String eventType = eventType(command.action());
    long revision =
        insertRevision(
            command, resolvedScopeId, eventType, before.map(ConfigRecord::revision).orElse(null));
    String plainContent = command.action() == ConfigMutationAction.DELETE ? "" : command.content();
    String checksum = checksum(plainContent);
    String storedContent =
        sensitiveConfigCodec.encryptIfSensitive(command.sensitive(), plainContent);
    String releaseNo = "REL-" + revision;
    ReleaseInsertResult release =
        jdbcTemplate.queryForObject(
            INSERT_RELEASE_SQL,
            this::mapReleaseInsertResult,
            releaseNo,
            command.configId(),
            resolvedScopeId,
            version,
            revision,
            storedContent,
            checksum,
            releaseStatus,
            releaseTitle(command.action(), command.configName()),
            command.reason(),
            command.operator());
    long releaseId = release == null ? 0 : release.id();
    OffsetDateTime releasedAt = release == null ? OffsetDateTime.now() : release.releasedAt();
    jdbcTemplate.update(UPDATE_REVISION_SOURCE_SQL, releaseId, revision);
    if (clientVisible) {
      insertGovernanceRuleIndex(
          command, resolvedScopeId, releaseId, revision, releaseStatus, plainContent);
      insertChangeEvent(command, resolvedScopeId, revision, eventType, plainContent, checksum);
      insertHistory(command, resolvedScopeId, revision, before.orElse(null), plainContent);
      insertAudit(command, releaseNo, before.orElse(null), plainContent, checksum);
    }
    return new ConfigMutationResult(
        command.configId(),
        resolvedScopeId,
        releaseNo,
        version,
        revision,
        releaseStatus,
        checksum,
        releasedAt);
  }

  private void insertGovernanceRuleIndex(
      ConfigMutationCommand command,
      long scopeId,
      long releaseId,
      long revision,
      String releaseStatus,
      String plainContent) {
    if (!isGovernanceRule(command) || !"PUBLISHED".equals(releaseStatus)) {
      return;
    }
    JsonNode root = readJson(plainContent);
    jdbcTemplate.update(
        INSERT_GOVERNANCE_RULE_INDEX_SQL,
        command.configId(),
        scopeId,
        releaseId,
        revision,
        command.ownerType(),
        command.ownerId(),
        command.env(),
        command.region(),
        command.zone(),
        command.cluster(),
        root.path("ruleType").asText("").toUpperCase(),
        root.path("targetService").asText(""),
        root.path("status").asText("").toUpperCase(),
        root.path("priority").asInt(0));
  }

  private boolean isGovernanceRule(ConfigMutationCommand command) {
    return GOVERNANCE_NAMESPACE.equals(command.namespaceCode())
        && GOVERNANCE_GROUP.equals(command.groupCode());
  }

  @Override
  public Optional<ConfigRecord> findLatest(
      String configId, String env, String region, String zone, String cluster) {
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(
              FIND_LATEST_SQL, this::mapRecord, configId, env, region, zone, cluster));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  @Override
  public List<ConfigRecord> findGovernanceRules(
      String env, String ownerId, String ruleType, String targetService, String status) {
    return jdbcTemplate.query(
        FIND_GOVERNANCE_RULES_SQL,
        this::mapRecord,
        env,
        blankToEmpty(ownerId),
        blankToEmpty(ownerId),
        blankToEmpty(ruleType),
        blankToEmpty(ruleType),
        blankToEmpty(targetService),
        blankToEmpty(targetService),
        blankToEmpty(status),
        blankToEmpty(status));
  }

  @Override
  public List<ControlPlaneAppConfigRecord> findControlPlaneConfigs(
      String ownerType,
      String ownerId,
      String namespaceCode,
      String env,
      String cluster,
      String group) {
    return jdbcTemplate.query(
        FIND_CONTROL_PLANE_CONFIGS_SQL,
        this::mapControlPlaneConfig,
        ownerType,
        ownerId,
        namespaceCode,
        blankToEmpty(env),
        blankToEmpty(env),
        blankToEmpty(cluster),
        blankToEmpty(cluster),
        blankToEmpty(group),
        blankToEmpty(group));
  }

  @Override
  public Optional<ControlPlaneAppConfigRecord> findControlPlaneConfig(
      String ownerType, String ownerId, String namespaceCode, String configId) {
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(
              FIND_CONTROL_PLANE_CONFIG_SQL,
              this::mapControlPlaneConfig,
              ownerType,
              ownerId,
              namespaceCode,
              configId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private long nextVersion(String configId, long scopeId) {
    Long version = jdbcTemplate.queryForObject(NEXT_VERSION_SQL, Long.class, configId, scopeId);
    return version == null ? 1 : version;
  }

  private long insertRevision(
      ConfigMutationCommand command, long scopeId, String eventType, Long beforeRevision) {
    Long revision =
        jdbcTemplate.queryForObject(
            INSERT_REVISION_SQL,
            Long.class,
            revisionType(command.action()),
            command.configId(),
            scopeId,
            eventType,
            command.reason(),
            writeJson(
                Map.of(
                    "configId",
                    command.configId(),
                    "beforeRevision",
                    beforeRevision == null ? 0 : beforeRevision)),
            command.operator());
    return revision == null ? 0 : revision;
  }

  private void insertChangeEvent(
      ConfigMutationCommand command,
      long scopeId,
      long revision,
      String eventType,
      String content,
      String checksum) {
    jdbcTemplate.update(
        INSERT_CHANGE_EVENT_SQL,
        revision,
        command.configId(),
        scopeId,
        command.env(),
        eventType,
        writeJson(Map.of("checksum", checksum, "contentLength", content.length())));
  }

  private void insertHistory(
      ConfigMutationCommand command,
      long scopeId,
      long revision,
      ConfigRecord before,
      String afterValue) {
    jdbcTemplate.update(
        INSERT_HISTORY_SQL,
        command.configId(),
        scopeId,
        historyType(command.action()),
        before == null
            ? null
            : sensitiveConfigCodec.maskIfSensitive(before.sensitive(), before.content()),
        sensitiveConfigCodec.maskIfSensitive(command.sensitive(), afterValue),
        before == null ? null : before.revision(),
        revision,
        command.operator(),
        command.reason());
  }

  private void insertAudit(
      ConfigMutationCommand command,
      String releaseNo,
      ConfigRecord before,
      String content,
      String checksum) {
    jdbcTemplate.update(
        INSERT_AUDIT_SQL,
        releaseNo,
        command.action().name(),
        command.operator(),
        writeJson(before == null ? Map.of() : Map.of("checksum", before.checksum())),
        writeJson(Map.of("checksum", checksum, "contentLength", content.length())));
  }

  private String releaseStatus(ConfigMutationAction action) {
    return action == ConfigMutationAction.DELETE ? "DELETED" : "PUBLISHED";
  }

  private String eventType(ConfigMutationAction action) {
    return action == ConfigMutationAction.DELETE ? "DELETED" : "PUBLISHED";
  }

  private String revisionType(ConfigMutationAction action) {
    return action == ConfigMutationAction.DELETE ? "DELETE" : "BASE_RELEASE";
  }

  private String historyType(ConfigMutationAction action) {
    return action == ConfigMutationAction.DELETE ? "BASE_DELETE" : "BASE_PUBLISH";
  }

  private String releaseTitle(ConfigMutationAction action, String configName) {
    return (action == ConfigMutationAction.DELETE ? "Delete " : "Publish ") + configName;
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

  private JsonNode readJson(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to parse JSON value", ex);
    }
  }

  private String blankToEmpty(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private ReleaseInsertResult mapReleaseInsertResult(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ReleaseInsertResult(
        resultSet.getLong("id"), toOffsetDateTime(resultSet.getTimestamp("released_at")));
  }

  private ConfigRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ConfigRecord(
        resultSet.getString("config_id"),
        resultSet.getString("config_name"),
        resultSet.getString("owner_type"),
        resultSet.getString("owner_id"),
        resultSet.getString("namespace_code"),
        resultSet.getString("group_code"),
        resultSet.getString("content_type"),
        resultSet.getBoolean("sensitive"),
        resultSet.getLong("scope_id"),
        resultSet.getString("env"),
        resultSet.getString("region"),
        resultSet.getString("zone"),
        resultSet.getString("cluster"),
        resultSet.getString("scope_mode"),
        resultSet.getString("release_no"),
        resultSet.getLong("version"),
        resultSet.getLong("revision"),
        sensitiveConfigCodec.decryptIfSensitive(
            resultSet.getBoolean("sensitive"), resultSet.getString("content")),
        resultSet.getString("checksum"),
        resultSet.getString("release_status"),
        toOffsetDateTime(resultSet.getTimestamp("released_at")),
        nullableString(resultSet, "governance_rule_type"),
        nullableString(resultSet, "governance_target_service"),
        nullableString(resultSet, "governance_status"),
        nullableInteger(resultSet, "governance_priority"));
  }

  private ControlPlaneAppConfigRecord mapControlPlaneConfig(ResultSet resultSet, int rowNumber)
      throws SQLException {
    boolean sensitive = resultSet.getBoolean("sensitive");
    String releaseStatus = resultSet.getString("release_status");
    OffsetDateTime releasedAt = toOffsetDateTime(resultSet.getTimestamp("released_at"));
    OffsetDateTime publishedAt = toOffsetDateTime(resultSet.getTimestamp("published_at"));
    boolean published = "PUBLISHED".equals(releaseStatus);
    return new ControlPlaneAppConfigRecord(
        resultSet.getString("config_id"),
        resultSet.getString("owner_id"),
        resultSet.getString("config_name"),
        resultSet.getString("description"),
        resultSet.getString("env"),
        resultSet.getString("cluster"),
        resultSet.getString("group_code"),
        resolveControlPlaneFormat(
            resultSet.getString("config_format"),
            resultSet.getString("config_name"),
            resultSet.getString("content_type")),
        resultSet.getLong("version"),
        releaseStatus,
        sensitiveConfigCodec.decryptIfSensitive(sensitive, resultSet.getString("content")),
        resultSet.getString("created_by"),
        releasedAt,
        published ? releasedAt : publishedAt,
        published || resultSet.getBoolean("format_locked"));
  }

  private String resolveControlPlaneFormat(
      String configFormat, String configName, String contentType) {
    String normalizedFormat = blankToEmpty(configFormat).toLowerCase();
    if (isSupportedControlPlaneFormat(normalizedFormat)) {
      return normalizedFormat;
    }
    int extensionIndex = configName == null ? -1 : configName.lastIndexOf('.');
    if (extensionIndex >= 0 && extensionIndex < configName.length() - 1) {
      String extension = configName.substring(extensionIndex + 1).toLowerCase();
      if ("txt".equals(extension)) {
        return "text";
      }
      if (isSupportedControlPlaneFormat(extension)) {
        return extension;
      }
    }
    return "KV".equalsIgnoreCase(contentType) ? "properties" : "yaml";
  }

  private boolean isSupportedControlPlaneFormat(String value) {
    return "yaml".equals(value)
        || "properties".equals(value)
        || "json".equals(value)
        || "toml".equals(value)
        || "text".equals(value);
  }

  private String nullableString(ResultSet resultSet, String columnName) throws SQLException {
    try {
      return resultSet.getString(columnName);
    } catch (SQLException ex) {
      return null;
    }
  }

  private Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
    try {
      int value = resultSet.getInt(columnName);
      return resultSet.wasNull() ? null : value;
    } catch (SQLException ex) {
      return null;
    }
  }

  private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
    return timestamp == null
        ? null
        : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
  }

  private record ReleaseInsertResult(long id, OffsetDateTime releasedAt) {}

  private static class HexFormatHolder {
    private static final java.util.HexFormat HEX = java.util.HexFormat.of();
  }
}

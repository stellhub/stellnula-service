package io.github.stellnula.repository;

import io.github.stellnula.application.SensitiveConfigCodec;
import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayRule;
import io.github.stellnula.domain.ConfigScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcConfigReleaseRepository implements ConfigReleaseRepository {

  private static final String LOAD_LATEST_PUBLISHED_SQL =
      """
      with latest_release as (
          select distinct on (config_id, scope_id)
                 id,
                 release_no,
                 config_id,
                 scope_id,
                 version,
                 revision,
                 content,
                 checksum,
                 release_status
            from stn_config_release
           order by config_id, scope_id, version desc
      )
      select
             d.config_id,
             d.config_name,
             d.owner_type,
             d.owner_id,
             d.namespace_code,
             d.group_code,
             d.content_type,
             d.sensitive,
             s.id as scope_id,
             s.env,
             s.region,
             s.zone,
             s.cluster,
             s.scope_mode,
             r.version,
             r.revision,
             r.content,
             r.checksum,
             r.release_status
        from latest_release r
        join stn_config_definition d
          on d.config_id = r.config_id
        join stn_config_scope s
          on s.id = r.scope_id
       where r.release_status in ('PUBLISHED', 'DELETED')
         and d.deleted = false
       order by r.config_id, r.scope_id
      """;

  private static final String LOAD_CLIENT_VISIBLE_GRAY_RULES_SQL =
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
             g.end_time
        from stn_config_gray_rule g
        join stn_config_definition d
          on d.config_id = g.config_id
        join stn_config_scope s
          on s.id = g.scope_id
       where g.status in ('ACTIVE', 'ENDED')
         and d.deleted = false
       order by g.config_id, g.scope_id, g.priority, g.id
      """;

  private static final String LOAD_RECENT_RELEASE_EVENTS_SQL =
      """
      select d.config_id,
             d.config_name,
             d.owner_type,
             d.owner_id,
             d.namespace_code,
             d.group_code,
             d.content_type,
             d.sensitive,
             s.id as scope_id,
             s.env,
             s.region,
             s.zone,
             s.cluster,
             s.scope_mode,
             r.version,
             r.revision,
             r.content,
             r.checksum,
             r.release_status
        from stn_config_release r
        join stn_config_definition d
          on d.config_id = r.config_id
        join stn_config_scope s
          on s.id = r.scope_id
       where r.release_status in ('PUBLISHED', 'DELETED')
         and d.deleted = false
       order by r.revision desc
      limit ?
      """;

  private static final String LOAD_RELEASE_EVENTS_AFTER_SQL =
      """
      select d.config_id,
             d.config_name,
             d.owner_type,
             d.owner_id,
             d.namespace_code,
             d.group_code,
             d.content_type,
             d.sensitive,
             s.id as scope_id,
             s.env,
             s.region,
             s.zone,
             s.cluster,
             s.scope_mode,
             r.version,
             r.revision,
             r.content,
             r.checksum,
             r.release_status
        from stn_config_release r
        join stn_config_definition d
          on d.config_id = r.config_id
        join stn_config_scope s
          on s.id = r.scope_id
       where r.release_status in ('PUBLISHED', 'DELETED')
         and r.revision > ?
         and d.deleted = false
       order by r.revision asc
       limit ?
      """;

  private static final String LOAD_RECENT_GRAY_RULE_EVENTS_SQL =
      """
      select g.id,
             g.config_id,
             g.scope_id,
             g.gray_name,
             g.rule_type,
             g.gray_rules::text as gray_rules,
             coalesce(h.after_value, g.config_value) as config_value,
             d.sensitive,
             coalesce(h.after_revision, g.effective_revision) as effective_revision,
             g.gray_version,
             g.checksum,
             g.priority,
             case when h.release_type = 'GRAY_END' then 'ENDED' else 'ACTIVE' end as status,
             g.start_time,
             g.end_time
        from stn_config_release_history h
        join stn_config_gray_rule g
          on g.id = h.gray_rule_id
        join stn_config_definition d
          on d.config_id = h.config_id
        join stn_config_scope s
          on s.id = h.scope_id
       where h.release_type in (
           'GRAY_CREATE',
           'GRAY_RULE_UPDATE',
           'GRAY_PUBLISH',
           'GRAY_ROLLBACK',
           'GRAY_FULL_RELEASE',
           'GRAY_END'
       )
         and h.after_revision is not null
         and d.deleted = false
       order by h.after_revision desc
      limit ?
      """;

  private static final String LOAD_CHANGE_EVENT_REVISIONS_AFTER_SQL =
      """
      select revision
        from stn_change_event
       where revision > ?
         and event_type in (
             'PUBLISHED',
             'DELETED',
             'ROLLED_BACK',
             'COPIED',
             'GRAY_PUBLISHED',
             'GRAY_RULE_CHANGED',
             'GRAY_ROLLED_BACK',
             'GRAY_FULL_RELEASE',
             'GRAY_ENDED'
         )
       order by revision asc
       limit ?
      """;

  private static final String LOAD_GRAY_RULE_EVENTS_AFTER_SQL =
      """
      select g.id,
             g.config_id,
             g.scope_id,
             g.gray_name,
             g.rule_type,
             g.gray_rules::text as gray_rules,
             coalesce(h.after_value, g.config_value) as config_value,
             d.sensitive,
             coalesce(h.after_revision, g.effective_revision) as effective_revision,
             g.gray_version,
             g.checksum,
             g.priority,
             case when h.release_type = 'GRAY_END' then 'ENDED' else 'ACTIVE' end as status,
             g.start_time,
             g.end_time
        from stn_config_release_history h
        join stn_config_gray_rule g
          on g.id = h.gray_rule_id
        join stn_config_definition d
          on d.config_id = h.config_id
        join stn_config_scope s
          on s.id = h.scope_id
       where h.release_type in (
           'GRAY_CREATE',
           'GRAY_RULE_UPDATE',
           'GRAY_PUBLISH',
           'GRAY_ROLLBACK',
           'GRAY_FULL_RELEASE',
           'GRAY_END'
       )
         and h.after_revision is not null
         and h.after_revision > ?
         and d.deleted = false
       order by h.after_revision asc
       limit ?
      """;

  private static final String MAX_REVISION_SQL =
      """
      select greatest(
          coalesce((select max(revision)
                      from stn_config_release
                     where release_status in ('PUBLISHED', 'DELETED')), 0),
          coalesce((select max(effective_revision)
                      from stn_config_gray_rule
                     where status in ('ACTIVE', 'ENDED')), 0),
          coalesce((select max(revision)
                      from stn_change_event
                     where event_type in (
                         'PUBLISHED',
                         'DELETED',
                         'ROLLED_BACK',
                         'COPIED',
                         'GRAY_PUBLISHED',
                         'GRAY_RULE_CHANGED',
                         'GRAY_ROLLED_BACK',
                         'GRAY_FULL_RELEASE',
                         'GRAY_ENDED'
                     )), 0),
          coalesce((select max(revision)
                      from stn_config_revision
                     where event_type in (
                         'PUBLISHED',
                         'DELETED',
                         'ROLLED_BACK',
                         'COPIED',
                         'GRAY_PUBLISHED',
                         'GRAY_RULE_CHANGED',
                         'GRAY_ROLLED_BACK',
                         'GRAY_FULL_RELEASE',
                         'GRAY_ENDED',
                         'CACHE_REBUILD'
                     )), 0)
      ) as max_revision
      """;

  private static final String UPSERT_CLIENT_SNAPSHOT_SQL =
      """
      insert into stn_client_snapshot (
          app_id,
          client_id,
          env,
          region,
          zone,
          cluster,
          namespace_code,
          group_code,
          local_revision,
          local_checksum,
          local_file_loaded,
          last_success_sync_at,
          last_heartbeat_at
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
      on conflict (app_id, client_id, env, region, zone, cluster, namespace_code, group_code)
      do update set
          local_revision = excluded.local_revision,
          local_checksum = excluded.local_checksum,
          local_file_loaded = excluded.local_file_loaded,
          last_success_sync_at = excluded.last_success_sync_at,
          last_heartbeat_at = now()
      """;

  private final JdbcTemplate jdbcTemplate;
  private final SensitiveConfigCodec sensitiveConfigCodec;

  @Override
  public List<ConfigEntry> loadLatestPublishedEntries() {
    return jdbcTemplate.query(LOAD_LATEST_PUBLISHED_SQL, this::mapEntry);
  }

  @Override
  public List<ConfigGrayRule> loadClientVisibleGrayRules() {
    return jdbcTemplate.query(LOAD_CLIENT_VISIBLE_GRAY_RULES_SQL, this::mapGrayRule);
  }

  @Override
  public List<ConfigEntry> loadRecentReleaseEvents(int limit) {
    return jdbcTemplate.query(LOAD_RECENT_RELEASE_EVENTS_SQL, this::mapEntry, limit);
  }

  @Override
  public List<ConfigEntry> loadReleaseEventsAfter(long revision, int limit) {
    return jdbcTemplate.query(LOAD_RELEASE_EVENTS_AFTER_SQL, this::mapEntry, revision, limit);
  }

  @Override
  public List<Long> loadChangeEventRevisionsAfter(long revision, int limit) {
    return jdbcTemplate.queryForList(
        LOAD_CHANGE_EVENT_REVISIONS_AFTER_SQL, Long.class, revision, limit);
  }

  @Override
  public List<ConfigGrayRule> loadRecentGrayRuleEvents(int limit) {
    return jdbcTemplate.query(LOAD_RECENT_GRAY_RULE_EVENTS_SQL, this::mapGrayRule, limit);
  }

  @Override
  public List<ConfigGrayRule> loadGrayRuleEventsAfter(long revision, int limit) {
    return jdbcTemplate.query(LOAD_GRAY_RULE_EVENTS_AFTER_SQL, this::mapGrayRule, revision, limit);
  }

  @Override
  public long findMaxRevision() {
    Long revision = jdbcTemplate.queryForObject(MAX_REVISION_SQL, Long.class);
    return revision == null ? 0 : revision;
  }

  @Override
  public void upsertClientSnapshot(ClientSnapshotState state) {
    jdbcTemplate.update(
        UPSERT_CLIENT_SNAPSHOT_SQL,
        state.appId(),
        state.clientId(),
        state.env(),
        state.region(),
        state.zone(),
        state.cluster(),
        state.namespaceCode(),
        state.groupCode(),
        state.localRevision(),
        state.localChecksum(),
        state.localFileLoaded(),
        toTimestamp(state.lastSuccessSyncAt()));
  }

  private ConfigEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
    ConfigScope scope =
        new ConfigScope(
            resultSet.getLong("scope_id"),
            resultSet.getString("env"),
            resultSet.getString("region"),
            resultSet.getString("zone"),
            resultSet.getString("cluster"),
            resultSet.getString("scope_mode"));
    return new ConfigEntry(
        resultSet.getString("config_id"),
        resultSet.getString("config_name"),
        resultSet.getString("owner_type"),
        resultSet.getString("owner_id"),
        resultSet.getString("namespace_code"),
        resultSet.getString("group_code"),
        resultSet.getString("content_type"),
        sensitiveConfigCodec.decryptIfSensitive(
            resultSet.getBoolean("sensitive"), resultSet.getString("content")),
        resultSet.getLong("version"),
        resultSet.getLong("revision"),
        resultSet.getBoolean("sensitive"),
        scope,
        "DELETED".equals(resultSet.getString("release_status")));
  }

  private ConfigGrayRule mapGrayRule(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ConfigGrayRule(
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
        toOffsetDateTime(resultSet.getTimestamp("end_time")));
  }

  private Timestamp toTimestamp(OffsetDateTime time) {
    return time == null ? null : Timestamp.from(time.toInstant());
  }

  private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
    return timestamp == null
        ? null
        : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
  }
}

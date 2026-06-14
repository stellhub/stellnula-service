package io.github.stellnula.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcClientDataPlaneRepository implements ClientDataPlaneRepository {

  private static final String UPSERT_CLIENT_INSTANCE_SQL =
      """
      insert into client_instance (
          app_id,
          client_id,
          env,
          region,
          zone,
          cluster,
          namespace_code,
          group_code,
          client_ip,
          host_name,
          sdk_version,
          labels,
          metadata,
          status,
          last_seen_at,
          updated_at
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, now(), now())
      on conflict (app_id, client_id, env, region, zone, cluster, namespace_code, group_code)
      do update set
          client_ip = excluded.client_ip,
          host_name = excluded.host_name,
          sdk_version = coalesce(excluded.sdk_version, client_instance.sdk_version),
          labels = excluded.labels,
          metadata = excluded.metadata,
          status = excluded.status,
          last_seen_at = now(),
          updated_at = now()
      """;

  private static final String UPSERT_CLIENT_SUBSCRIPTION_SQL =
      """
      insert into client_subscription (
          app_id,
          client_id,
          env,
          region,
          zone,
          cluster,
          namespace_code,
          group_code,
          subscription_type,
          subscription_key,
          current_revision,
          current_checksum,
          transport,
          status,
          last_watch_at,
          updated_at
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
      on conflict (
          app_id,
          client_id,
          env,
          region,
          zone,
          cluster,
          namespace_code,
          group_code,
          subscription_type,
          subscription_key
      )
      do update set
          current_revision = excluded.current_revision,
          current_checksum = excluded.current_checksum,
          transport = excluded.transport,
          status = excluded.status,
          last_watch_at = now(),
          updated_at = now()
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  @Override
  public void upsertClientInstance(ClientInstanceState state) {
    jdbcTemplate.update(
        UPSERT_CLIENT_INSTANCE_SQL,
        state.appId(),
        state.clientId(),
        state.env(),
        state.region(),
        state.zone(),
        state.cluster(),
        state.namespaceCode(),
        state.groupCode(),
        blankToNull(state.clientIp()),
        blankToNull(state.hostName()),
        blankToNull(state.sdkVersion()),
        writeJson(state.labels()),
        writeJson(state.metadata()),
        state.status());
  }

  @Override
  public void upsertClientSubscription(ClientSubscriptionState state) {
    jdbcTemplate.update(
        UPSERT_CLIENT_SUBSCRIPTION_SQL,
        state.appId(),
        state.clientId(),
        state.env(),
        state.region(),
        state.zone(),
        state.cluster(),
        state.namespaceCode(),
        state.groupCode(),
        state.subscriptionType(),
        state.subscriptionKey(),
        state.currentRevision(),
        state.currentChecksum(),
        state.transport(),
        state.status());
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to serialize JSON value", ex);
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}

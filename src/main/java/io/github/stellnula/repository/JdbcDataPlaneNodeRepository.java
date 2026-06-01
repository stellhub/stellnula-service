package io.github.stellnula.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.domain.DataPlaneNodeRecord;
import io.github.stellnula.domain.ServerEndpoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcDataPlaneNodeRepository implements DataPlaneNodeRepository {

  private static final String UPSERT_NODE_SQL =
      """
      insert into stn_data_plane_node (
          server_id,
          http_address,
          grpc_address,
          region,
          zone,
          weight,
          status,
          healthy,
          active_watch_count,
          load_score,
          metadata,
          last_heartbeat_at,
          updated_at
      ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), now())
      on conflict (server_id)
      do update set
          http_address = excluded.http_address,
          grpc_address = excluded.grpc_address,
          region = excluded.region,
          zone = excluded.zone,
          weight = excluded.weight,
          status = case
              when stn_data_plane_node.status = 'DRAINING' and excluded.status = 'ACTIVE'
                  then 'DRAINING'
              else excluded.status
          end,
          healthy = case
              when stn_data_plane_node.status = 'DRAINING' and excluded.status = 'ACTIVE'
                  then false
              else excluded.healthy
          end,
          active_watch_count = excluded.active_watch_count,
          load_score = excluded.load_score,
          metadata = excluded.metadata,
          drain_started_at = case
              when excluded.status = 'DRAINING' then coalesce(stn_data_plane_node.drain_started_at, now())
              when excluded.status = 'ACTIVE' and stn_data_plane_node.status <> 'DRAINING' then null
              else stn_data_plane_node.drain_started_at
          end,
          offline_at = case
              when excluded.status = 'ACTIVE' then null
              else stn_data_plane_node.offline_at
          end,
          last_heartbeat_at = now(),
          updated_at = now()
      """;

  private static final String FIND_HEALTHY_NODES_SQL =
      """
      select server_id,
             http_address,
             grpc_address,
             weight,
             region,
             zone,
             healthy,
             status,
             active_watch_count,
             load_score,
             failure_count
       from stn_data_plane_node
       where status = 'ACTIVE'
         and healthy = true
         and failure_count < ?
         and last_heartbeat_at >= now() - (? * interval '1 millisecond')
       order by region, zone, load_score, active_watch_count, weight desc, server_id
      """;

  private static final String FIND_PROBE_CANDIDATES_SQL =
      """
      select server_id,
             http_address,
             grpc_address,
             region,
             zone,
             weight,
             status,
             healthy,
             active_watch_count,
             load_score,
             failure_count,
             metadata::text as metadata,
             last_probe_at,
             drain_started_at,
             offline_at,
             last_heartbeat_at,
             registered_at,
             updated_at
        from stn_data_plane_node
       where status in ('ACTIVE', 'DRAINING')
         and last_heartbeat_at >= now() - (? * interval '1 millisecond')
       order by region, zone, server_id
      """;

  private static final String FIND_ALL_NODES_SQL =
      """
      select server_id,
             http_address,
             grpc_address,
             region,
             zone,
             weight,
             status,
             healthy,
             active_watch_count,
             load_score,
             failure_count,
             metadata::text as metadata,
             last_probe_at,
             drain_started_at,
             offline_at,
             last_heartbeat_at,
             registered_at,
             updated_at
        from stn_data_plane_node
       order by region, zone, server_id
      """;

  private static final String MARK_EXPIRED_NODES_OFFLINE_SQL =
      """
      update stn_data_plane_node
         set status = 'OFFLINE',
             healthy = false,
             updated_at = now()
       where status <> 'OFFLINE'
         and last_heartbeat_at < now() - (? * interval '1 millisecond')
      """;

  private static final String UPDATE_NODE_STATUS_SQL =
      """
      update stn_data_plane_node
         set status = ?,
             healthy = ?,
             metadata = metadata || ?::jsonb,
             drain_started_at = case
                 when ? = 'DRAINING' then now()
                 when ? = 'ACTIVE' then null
                 else drain_started_at
             end,
             offline_at = case
                 when ? = 'OFFLINE' then now()
                 when ? = 'ACTIVE' then null
                 else offline_at
             end,
             failure_count = case when ? = 'ACTIVE' then 0 else failure_count end,
             updated_at = now()
       where server_id = ?
      """;

  private static final String RECORD_PROBE_RESULT_SQL =
      """
      update stn_data_plane_node
         set failure_count = case when ? then 0 else failure_count + 1 end,
             healthy = case when ? then (status = 'ACTIVE') else false end,
             last_probe_at = now(),
             updated_at = now()
       where server_id = ?
      """;

  private static final String MARK_PROBE_FAILED_NODES_OFFLINE_SQL =
      """
      update stn_data_plane_node
         set status = 'OFFLINE',
             healthy = false,
             offline_at = coalesce(offline_at, now()),
             updated_at = now()
       where status = 'ACTIVE'
         and failure_count >= ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  @Override
  public void upsertCurrentNode(DataPlaneNodeRegistration registration) {
    jdbcTemplate.update(
        UPSERT_NODE_SQL,
        registration.serverId(),
        registration.httpAddress(),
        registration.grpcAddress(),
        registration.region(),
        registration.zone(),
        registration.weight(),
        registration.status(),
        registration.healthy(),
        registration.activeWatchCount(),
        registration.loadScore(),
        writeJson(registration.metadata()));
  }

  @Override
  public List<ServerEndpoint> findHealthyNodes(long expireMillis, int failureThreshold) {
    return jdbcTemplate.query(
        FIND_HEALTHY_NODES_SQL,
        (resultSet, rowNumber) ->
            new ServerEndpoint(
                resultSet.getString("server_id"),
                resultSet.getString("http_address"),
                resultSet.getString("grpc_address"),
                resultSet.getInt("weight"),
                resultSet.getString("region"),
                resultSet.getString("zone"),
                resultSet.getBoolean("healthy"),
                resultSet.getString("status"),
                resultSet.getInt("active_watch_count"),
                resultSet.getDouble("load_score"),
                resultSet.getInt("failure_count")),
        failureThreshold,
        expireMillis);
  }

  @Override
  public List<DataPlaneNodeRecord> findProbeCandidates(long expireMillis) {
    return jdbcTemplate.query(FIND_PROBE_CANDIDATES_SQL, this::mapNodeRecord, expireMillis);
  }

  @Override
  public List<DataPlaneNodeRecord> findAllNodes() {
    return jdbcTemplate.query(FIND_ALL_NODES_SQL, this::mapNodeRecord);
  }

  @Override
  public void updateNodeStatus(String serverId, String status, boolean healthy, String reason) {
    jdbcTemplate.update(
        UPDATE_NODE_STATUS_SQL,
        status,
        healthy,
        writeJson(Map.of("lifecycleReason", reason == null ? "" : reason)),
        status,
        status,
        status,
        status,
        status,
        serverId);
  }

  @Override
  public void recordProbeResult(String serverId, boolean success) {
    jdbcTemplate.update(RECORD_PROBE_RESULT_SQL, success, success, serverId);
  }

  @Override
  public int markProbeFailedNodesOffline(int failureThreshold) {
    return jdbcTemplate.update(MARK_PROBE_FAILED_NODES_OFFLINE_SQL, failureThreshold);
  }

  @Override
  public int markExpiredNodesOffline(long expireMillis) {
    return jdbcTemplate.update(MARK_EXPIRED_NODES_OFFLINE_SQL, expireMillis);
  }

  private DataPlaneNodeRecord mapNodeRecord(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new DataPlaneNodeRecord(
        resultSet.getString("server_id"),
        resultSet.getString("http_address"),
        resultSet.getString("grpc_address"),
        resultSet.getString("region"),
        resultSet.getString("zone"),
        resultSet.getInt("weight"),
        resultSet.getString("status"),
        resultSet.getBoolean("healthy"),
        resultSet.getInt("active_watch_count"),
        resultSet.getDouble("load_score"),
        resultSet.getInt("failure_count"),
        readMetadata(resultSet.getString("metadata")),
        resultSet.getObject("last_probe_at", OffsetDateTime.class),
        resultSet.getObject("drain_started_at", OffsetDateTime.class),
        resultSet.getObject("offline_at", OffsetDateTime.class),
        resultSet.getObject("last_heartbeat_at", OffsetDateTime.class),
        resultSet.getObject("registered_at", OffsetDateTime.class),
        resultSet.getObject("updated_at", OffsetDateTime.class));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to serialize JSON value", ex);
    }
  }

  private Map<String, String> readMetadata(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to parse node metadata", ex);
    }
  }
}

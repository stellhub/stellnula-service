package io.github.stellnula.api.http;

import io.github.stellnula.application.DataPlaneNodeService;
import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.DataPlaneNodeRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/data-plane/nodes")
public class DataPlaneNodeController {

  private final DataPlaneNodeService nodeService;
  private final DataPlaneProperties properties;

  /** 查询数据面节点状态和负载。 */
  @GetMapping
  public List<NodeResponse> listNodes() {
    return nodeService.listNodes().stream().map(NodeResponse::from).toList();
  }

  /** 将指定节点切换为 DRAINING。 */
  @PostMapping("/{serverId}/drain")
  public LifecycleResponse drain(
      @PathVariable @NotBlank String serverId,
      @Valid @RequestBody(required = false) LifecycleRequest request) {
    nodeService.drainNode(serverId, reason(request));
    return LifecycleResponse.of(serverId, "DRAINING", false, properties.addressTtlSeconds());
  }

  /** 将指定节点恢复为 ACTIVE。 */
  @PostMapping("/{serverId}/activate")
  public LifecycleResponse activate(
      @PathVariable @NotBlank String serverId,
      @Valid @RequestBody(required = false) LifecycleRequest request) {
    nodeService.activateNode(serverId, reason(request));
    return LifecycleResponse.of(serverId, "ACTIVE", true, properties.addressTtlSeconds());
  }

  /** 将指定节点切换为 OFFLINE。 */
  @PostMapping("/{serverId}/offline")
  public LifecycleResponse offline(
      @PathVariable @NotBlank String serverId,
      @Valid @RequestBody(required = false) LifecycleRequest request) {
    nodeService.offlineNode(serverId, reason(request));
    return LifecycleResponse.of(serverId, "OFFLINE", false, properties.addressTtlSeconds());
  }

  /** 将当前节点切换为 DRAINING。 */
  @PostMapping("/self/drain")
  public LifecycleResponse drainSelf(
      @Valid @RequestBody(required = false) LifecycleRequest request) {
    nodeService.drainNode(properties.serverId(), reason(request));
    return LifecycleResponse.of(
        properties.serverId(), "DRAINING", false, properties.addressTtlSeconds());
  }

  /** 将当前节点切换为 OFFLINE。 */
  @PostMapping("/self/offline")
  public LifecycleResponse offlineSelf(
      @Valid @RequestBody(required = false) LifecycleRequest request) {
    nodeService.offlineNode(properties.serverId(), reason(request));
    return LifecycleResponse.of(
        properties.serverId(), "OFFLINE", false, properties.addressTtlSeconds());
  }

  public record LifecycleRequest(String reason) {}

  private String reason(LifecycleRequest request) {
    return request == null || request.reason() == null ? "" : request.reason();
  }

  public record LifecycleResponse(
      String serverId,
      String status,
      boolean healthy,
      long addressTtlSeconds,
      List<String> clientReselectOn) {

    static LifecycleResponse of(
        String serverId, String status, boolean healthy, long addressTtlSeconds) {
      return new LifecycleResponse(
          serverId,
          status,
          healthy,
          addressTtlSeconds,
          List.of("ADDRESS_TTL_EXPIRED", "WATCH_ERROR", "SERVER_DRAINING", "UNHEALTHY"));
    }
  }

  public record NodeResponse(
      String serverId,
      String httpAddress,
      String grpcAddress,
      String region,
      String zone,
      int weight,
      String status,
      boolean healthy,
      int activeWatchCount,
      double loadScore,
      int failureCount,
      Map<String, String> metadata,
      OffsetDateTime lastProbeAt,
      OffsetDateTime drainStartedAt,
      OffsetDateTime offlineAt,
      OffsetDateTime lastHeartbeatAt,
      OffsetDateTime updatedAt) {

    static NodeResponse from(DataPlaneNodeRecord record) {
      return new NodeResponse(
          record.serverId(),
          record.httpAddress(),
          record.grpcAddress(),
          record.region(),
          record.zone(),
          record.weight(),
          record.status(),
          record.healthy(),
          record.activeWatchCount(),
          record.loadScore(),
          record.failureCount(),
          record.metadata(),
          record.lastProbeAt(),
          record.drainStartedAt(),
          record.offlineAt(),
          record.lastHeartbeatAt(),
          record.updatedAt());
    }
  }
}

package io.github.stellnula.repository;

import io.github.stellnula.domain.DataPlaneNodeRecord;
import io.github.stellnula.domain.ServerEndpoint;
import java.util.List;

public interface DataPlaneNodeRepository {

  /** 注册或刷新当前数据面节点。 */
  void upsertCurrentNode(DataPlaneNodeRegistration registration);

  /** 查询可返回给客户端的健康数据面节点。 */
  List<ServerEndpoint> findHealthyNodes(long expireMillis, int failureThreshold);

  /** 查询可用于主动健康探测的数据面节点。 */
  List<DataPlaneNodeRecord> findProbeCandidates(long expireMillis);

  /** 查询所有数据面节点。 */
  List<DataPlaneNodeRecord> findAllNodes();

  /** 更新节点生命周期状态。 */
  void updateNodeStatus(String serverId, String status, boolean healthy, String reason);

  /** 记录节点健康探测结果。 */
  void recordProbeResult(String serverId, boolean success);

  /** 将连续探测失败的节点标记为离线。 */
  int markProbeFailedNodesOffline(int failureThreshold);

  /** 将心跳过期的数据面节点标记为离线。 */
  int markExpiredNodesOffline(long expireMillis);
}

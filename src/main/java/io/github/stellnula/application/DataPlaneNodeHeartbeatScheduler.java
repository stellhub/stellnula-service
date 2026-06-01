package io.github.stellnula.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataPlaneNodeHeartbeatScheduler implements ApplicationRunner {

  private final DataPlaneNodeService nodeService;

  /** 启动时注册当前数据面节点。 */
  @Override
  public void run(ApplicationArguments args) {
    registerCurrentNode();
    refreshNodeCache();
  }

  /** 定时刷新当前数据面节点心跳。 */
  @Scheduled(fixedDelayString = "${stellnula.data-plane.heartbeat-millis}")
  public void registerCurrentNode() {
    try {
      nodeService.registerCurrentNode();
    } catch (RuntimeException ex) {
      log.warn("Failed to register Stellnula data-plane node", ex);
    }
  }

  /** 定时从 PostgreSQL 拉取健康节点列表到内存。 */
  @Scheduled(fixedDelayString = "${stellnula.data-plane.refresh-interval-millis}")
  public void refreshNodeCache() {
    try {
      nodeService.refreshNodeCache();
    } catch (RuntimeException ex) {
      log.warn("Failed to refresh Stellnula data-plane node cache", ex);
    }
  }
}

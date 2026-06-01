package io.github.stellnula.application;

import io.github.stellnula.config.DataPlaneProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataPlaneNodeShutdownHook implements ApplicationListener<ContextClosedEvent> {

  private final DataPlaneProperties properties;
  private final DataPlaneNodeService nodeService;

  /** Spring 关闭时先进入 DRAINING，等待客户端按地址 TTL 重选后再标记 OFFLINE。 */
  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    try {
      nodeService.drainNode(properties.serverId(), "spring-context-closing");
      long waitMillis =
          Math.min(properties.nodeDrainMillis(), properties.gracefulShutdownWaitMillis());
      if (waitMillis > 0) {
        Thread.sleep(waitMillis);
      }
      nodeService.offlineNode(properties.serverId(), "spring-context-closed");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      nodeService.offlineNode(properties.serverId(), "spring-context-close-interrupted");
    } catch (RuntimeException ex) {
      log.warn("Failed to update data-plane node lifecycle during shutdown", ex);
    }
  }
}

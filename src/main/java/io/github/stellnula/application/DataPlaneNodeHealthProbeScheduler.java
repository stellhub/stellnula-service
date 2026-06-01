package io.github.stellnula.application;

import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.DataPlaneNodeRecord;
import io.github.stellnula.repository.DataPlaneNodeRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataPlaneNodeHealthProbeScheduler {

  private final DataPlaneProperties properties;
  private final DataPlaneNodeRepository repository;
  private final DataPlaneNodeService nodeService;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  /** 定时探测其他数据面节点健康状态，连续失败后剔除。 */
  @Scheduled(fixedDelayString = "${stellnula.data-plane.refresh-interval-millis}")
  public void probeNodes() {
    try {
      for (DataPlaneNodeRecord node :
          repository.findProbeCandidates(properties.nodeExpireMillis())) {
        if (properties.serverId().equals(node.serverId())) {
          continue;
        }
        repository.recordProbeResult(node.serverId(), probe(node));
      }
      int offlineCount =
          repository.markProbeFailedNodesOffline(properties.nodeProbeFailureThreshold());
      if (offlineCount > 0) {
        nodeService.refreshNodeCache();
      }
    } catch (RuntimeException ex) {
      log.warn("Failed to probe Stellnula data-plane nodes", ex);
    }
  }

  private boolean probe(DataPlaneNodeRecord node) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(trimSlash(node.httpAddress()) + "/actuator/health"))
              .timeout(Duration.ofMillis(properties.nodeProbeTimeoutMillis()))
              .GET()
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() >= 200 && response.statusCode() < 300;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return false;
    } catch (RuntimeException | java.io.IOException ex) {
      return false;
    }
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}

package io.github.stellnula.cache;

import io.github.stellnula.domain.ServerEndpoint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class DataPlaneNodeCache {

  private final AtomicReference<NodeState> state =
      new AtomicReference<>(new NodeState(List.of(), OffsetDateTime.now()));

  /** 刷新数据面节点内存缓存。 */
  public void refresh(List<ServerEndpoint> endpoints) {
    state.set(new NodeState(endpoints, OffsetDateTime.now()));
  }

  /** 查询当前可用数据面节点。 */
  public List<ServerEndpoint> endpoints() {
    return state.get().endpoints();
  }

  private record NodeState(List<ServerEndpoint> endpoints, OffsetDateTime loadedAt) {

    private NodeState {
      endpoints = List.copyOf(endpoints);
    }
  }
}

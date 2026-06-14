package io.github.stellnula.application;

import io.github.stellnula.domain.DataPlaneNodeEndpoint;

public interface DataPlaneNodeEndpointResolver {

  /** 解析当前数据面节点对客户端和其他节点暴露的服务端点。 */
  DataPlaneNodeEndpoint current();
}

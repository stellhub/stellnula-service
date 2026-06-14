package io.github.stellnula.application;

import io.github.stellflux.grpc.server.StellfluxGrpcServerProperties;
import io.github.stellflux.http.server.StellfluxHttpServerProperties;
import io.github.stellnula.domain.DataPlaneNodeEndpoint;
import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class StellfluxDataPlaneNodeEndpointResolver implements DataPlaneNodeEndpointResolver {

  private static final int DEFAULT_HTTP_PORT = 8080;

  private final Environment environment;
  private final StellfluxHttpServerProperties httpServerProperties;
  private final StellfluxGrpcServerProperties grpcServerProperties;

  /** 基于 Stellflux/Spring Boot 服务端配置解析当前数据面节点端点。 */
  @Override
  public DataPlaneNodeEndpoint current() {
    String host = advertisedHost();
    return new DataPlaneNodeEndpoint(serverId(host), httpAddress(host), grpcAddress(host));
  }

  private String serverId(String host) {
    String instanceId =
        firstText(
            grpcServerProperties.getRegistration().getInstanceId(),
            httpServerProperties.getRegistration().getInstanceId());
    if (StringUtils.hasText(instanceId)) {
      return instanceId;
    }
    String applicationName =
        environment.getProperty("spring.application.name", "stellnula-service");
    return applicationName + "-" + host + "-" + grpcPort();
  }

  private String httpAddress(String host) {
    String scheme =
        environment.getProperty("server.ssl.enabled", Boolean.class, false) ? "https" : "http";
    String contextPath =
        normalizeContextPath(environment.getProperty("server.servlet.context-path"));
    return scheme + "://" + host + ":" + httpPort() + contextPath;
  }

  private String grpcAddress(String host) {
    return host + ":" + grpcPort();
  }

  private String advertisedHost() {
    String host =
        firstText(
            grpcServerProperties.getRegistration().getHost(),
            httpServerProperties.getRegistration().getHost(),
            environment.getProperty("server.address"));
    if (StringUtils.hasText(host) && !isWildcardAddress(host)) {
      return host;
    }
    return localHostName();
  }

  private int httpPort() {
    return environment.getProperty(
        "local.server.port",
        Integer.class,
        environment.getProperty("server.port", Integer.class, DEFAULT_HTTP_PORT));
  }

  private int grpcPort() {
    Integer advertisedPort = grpcServerProperties.getAdvertisedPort();
    return advertisedPort == null ? grpcServerProperties.getPort() : advertisedPort;
  }

  private String normalizeContextPath(String contextPath) {
    if (!StringUtils.hasText(contextPath) || "/".equals(contextPath)) {
      return "";
    }
    return contextPath.startsWith("/") ? contextPath : "/" + contextPath;
  }

  private boolean isWildcardAddress(String host) {
    return "0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host);
  }

  private String localHostName() {
    try {
      return InetAddress.getLocalHost().getHostAddress();
    } catch (UnknownHostException ex) {
      return "127.0.0.1";
    }
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return "";
  }
}

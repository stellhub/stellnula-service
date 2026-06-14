package io.github.stellnula.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stellflux.grpc.server.StellfluxGrpcServerProperties;
import io.github.stellflux.http.server.StellfluxHttpServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StellfluxDataPlaneNodeEndpointResolverTest {

  @Test
  void shouldResolveEndpointFromStellfluxAndSpringServerProperties() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.application.name", "stellnula-service")
            .withProperty("server.port", "18060")
            .withProperty("server.ssl.enabled", "false");
    StellfluxHttpServerProperties httpProperties = new StellfluxHttpServerProperties();
    StellfluxGrpcServerProperties grpcProperties = new StellfluxGrpcServerProperties();
    grpcProperties.setPort(19090);
    grpcProperties.setAdvertisedPort(29090);
    grpcProperties.getRegistration().setInstanceId("node-a");
    grpcProperties.getRegistration().setHost("10.0.0.11");

    StellfluxDataPlaneNodeEndpointResolver resolver =
        new StellfluxDataPlaneNodeEndpointResolver(environment, httpProperties, grpcProperties);

    var endpoint = resolver.current();

    assertThat(endpoint.serverId()).isEqualTo("node-a");
    assertThat(endpoint.httpAddress()).isEqualTo("http://10.0.0.11:18060");
    assertThat(endpoint.grpcAddress()).isEqualTo("10.0.0.11:29090");
  }
}

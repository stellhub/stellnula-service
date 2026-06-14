package io.github.stellnula.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stellnula.application.ConfigMutationService;
import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ControlPlaneAppConfigRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ControlPlaneAppConfigControllerTest {

  private final ConfigMutationService mutationService = mock(ConfigMutationService.class);
  private final ControlPlaneAppConfigController controller =
      new ControlPlaneAppConfigController(mutationService);

  @Test
  void saveDraftShouldMapFrontendPayloadToNulaMutationCommand() {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("DRAFT", false)));
    ControlPlaneAppConfigController.AppConfigRequest request =
        new ControlPlaneAppConfigController.AppConfigRequest(
            "checkout-yaml",
            "acme.checkout",
            "checkout.yaml",
            "checkout config",
            "prod",
            "default",
            null,
            "yaml",
            "app:\n  enabled: true\n",
            "xiaoy");

    ControlPlaneAppConfigController.AppConfigResponse response =
        controller.saveDraft("checkout-yaml", request, null, null);

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(mutationService).saveDraft(commandCaptor.capture());
    ConfigMutationCommand command = commandCaptor.getValue();
    assertThat(command.configId()).isEqualTo("checkout-yaml");
    assertThat(command.ownerId()).isEqualTo("acme.checkout");
    assertThat(command.namespaceCode()).isEqualTo("app-config");
    assertThat(command.groupCode()).isEqualTo("default");
    assertThat(command.format()).isEqualTo("yaml");
    assertThat(command.env()).isEqualTo("prod");
    assertThat(command.cluster()).isEqualTo("default");
    assertThat(response.status()).isEqualTo("draft");
  }

  @Test
  void saveDraftShouldAllowFrontendGroup() {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("DRAFT", false, "payment")));
    ControlPlaneAppConfigController.AppConfigRequest request =
        new ControlPlaneAppConfigController.AppConfigRequest(
            "checkout-yaml",
            "acme.checkout",
            "checkout.yaml",
            "checkout config",
            "prod",
            "default",
            "payment",
            "yaml",
            "app:\n  enabled: true\n",
            "xiaoy");

    ControlPlaneAppConfigController.AppConfigResponse response =
        controller.saveDraft("checkout-yaml", request, null, null);

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(mutationService).saveDraft(commandCaptor.capture());
    assertThat(commandCaptor.getValue().groupCode()).isEqualTo("payment");
    assertThat(response.group()).isEqualTo("payment");
    assertThat(response.format()).isEqualTo("yaml");
  }

  @Test
  void saveDraftShouldPassThroughTomlFormat() {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("DRAFT", false, "payment", "toml")));
    ControlPlaneAppConfigController.AppConfigRequest request =
        new ControlPlaneAppConfigController.AppConfigRequest(
            "checkout-toml",
            "acme.checkout",
            "checkout.toml",
            "checkout config",
            "prod",
            "default",
            "payment",
            "toml",
            "[app]\nenabled = true\n",
            "xiaoy");

    ControlPlaneAppConfigController.AppConfigResponse response =
        controller.saveDraft("checkout-toml", request, null, null);

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(mutationService).saveDraft(commandCaptor.capture());
    assertThat(commandCaptor.getValue().format()).isEqualTo("toml");
    assertThat(response.format()).isEqualTo("toml");
  }

  @Test
  void publishShouldReturnFrontendPublishedShape() {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("PUBLISHED", true)));
    ControlPlaneAppConfigController.AppConfigRequest request =
        new ControlPlaneAppConfigController.AppConfigRequest(
            "checkout-yaml",
            "acme.checkout",
            "checkout.yaml",
            "checkout config",
            "prod",
            "default",
            "payment",
            "yaml",
            "app:\n  enabled: true\n",
            "xiaoy");

    ControlPlaneAppConfigController.AppConfigResponse response =
        controller.publish("checkout-yaml", request, null, null);

    assertThat(response.id()).isEqualTo("checkout-yaml");
    assertThat(response.appId()).isEqualTo("acme.checkout");
    assertThat(response.status()).isEqualTo("published");
    assertThat(response.formatLocked()).isTrue();
    assertThat(response.version()).isEqualTo("v2");
    assertThat(response.publishedAt()).isEqualTo("2026-06-10T00:00:00Z");
  }

  private ControlPlaneAppConfigRecord record(String status, boolean formatLocked) {
    return record(status, formatLocked, "default");
  }

  private ControlPlaneAppConfigRecord record(String status, boolean formatLocked, String group) {
    return record(status, formatLocked, group, "yaml");
  }

  private ControlPlaneAppConfigRecord record(
      String status, boolean formatLocked, String group, String format) {
    OffsetDateTime time = OffsetDateTime.parse("2026-06-10T00:00:00Z");
    return new ControlPlaneAppConfigRecord(
        "checkout-yaml",
        "acme.checkout",
        "checkout.yaml",
        "checkout config",
        "prod",
        "default",
        group,
        format,
        2,
        status,
        "app:\n  enabled: true\n",
        "xiaoy",
        time,
        formatLocked ? time : null,
        formatLocked);
  }
}

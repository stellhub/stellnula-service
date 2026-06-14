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

class ControlPlaneCommonConfigControllerTest {

  private final ConfigMutationService mutationService = mock(ConfigMutationService.class);
  private final ControlPlaneCommonConfigController controller =
      new ControlPlaneCommonConfigController(mutationService);

  @Test
  void saveDraftShouldMapPayloadToPublicCommonConfigCommand() {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("DRAFT", false, "default")));
    ControlPlaneCommonConfigController.CommonConfigRequest request =
        new ControlPlaneCommonConfigController.CommonConfigRequest(
            "redis-yaml",
            null,
            "redis.yaml",
            "common redis config",
            "prod",
            "default",
            null,
            "yaml",
            "redis:\n  timeout: 3s\n",
            "xiaoy");

    ControlPlaneCommonConfigController.CommonConfigResponse response =
        controller.saveDraft("redis-yaml", request, null, null);

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(mutationService).saveDraft(commandCaptor.capture());
    ConfigMutationCommand command = commandCaptor.getValue();
    assertThat(command.configId()).isEqualTo("redis-yaml");
    assertThat(command.ownerType()).isEqualTo("PUBLIC");
    assertThat(command.ownerId()).isEqualTo("global");
    assertThat(command.namespaceCode()).isEqualTo("common-config");
    assertThat(command.groupCode()).isEqualTo("default");
    assertThat(command.format()).isEqualTo("yaml");
    assertThat(response.ownerId()).isEqualTo("global");
    assertThat(response.status()).isEqualTo("draft");
  }

  @Test
  void saveDraftShouldAllowCustomPublicOwnerAndGroup() {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("DRAFT", false, "redis")));
    ControlPlaneCommonConfigController.CommonConfigRequest request =
        new ControlPlaneCommonConfigController.CommonConfigRequest(
            "redis-yaml",
            "platform",
            "redis.yaml",
            "common redis config",
            "prod",
            "default",
            "redis",
            "yaml",
            "redis:\n  timeout: 3s\n",
            "xiaoy");

    ControlPlaneCommonConfigController.CommonConfigResponse response =
        controller.saveDraft("redis-yaml", request, null, null);

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(mutationService).saveDraft(commandCaptor.capture());
    assertThat(commandCaptor.getValue().ownerId()).isEqualTo("platform");
    assertThat(commandCaptor.getValue().groupCode()).isEqualTo("redis");
    assertThat(response.group()).isEqualTo("redis");
  }

  @Test
  void saveDraftShouldPassThroughTextFormat() {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("DRAFT", false, "notice", "text")));
    ControlPlaneCommonConfigController.CommonConfigRequest request =
        new ControlPlaneCommonConfigController.CommonConfigRequest(
            "notice-text",
            null,
            "notice.txt",
            "common notice",
            "prod",
            "default",
            "notice",
            "text",
            "plain notice",
            "xiaoy");

    ControlPlaneCommonConfigController.CommonConfigResponse response =
        controller.saveDraft("notice-text", request, null, null);

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(mutationService).saveDraft(commandCaptor.capture());
    assertThat(commandCaptor.getValue().format()).isEqualTo("text");
    assertThat(response.format()).isEqualTo("text");
  }

  private ControlPlaneAppConfigRecord record(String status, boolean formatLocked, String group) {
    return record(status, formatLocked, group, "yaml");
  }

  private ControlPlaneAppConfigRecord record(
      String status, boolean formatLocked, String group, String format) {
    OffsetDateTime time = OffsetDateTime.parse("2026-06-10T00:00:00Z");
    return new ControlPlaneAppConfigRecord(
        "redis-yaml",
        "global",
        "redis.yaml",
        "common redis config",
        "prod",
        "default",
        group,
        format,
        2,
        status,
        "redis:\n  timeout: 3s\n",
        "xiaoy",
        time,
        formatLocked ? time : null,
        formatLocked);
  }
}

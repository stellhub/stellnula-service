package io.github.stellnula.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stellnula.config.DataPlaneProperties;
import io.github.stellnula.domain.ConfigMutationAction;
import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ConfigMutationResult;
import io.github.stellnula.repository.ConfigMutationRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfigMutationServiceTest {

  private final ConfigMutationRepository repository = mock(ConfigMutationRepository.class);
  private final DataPlaneProperties properties = mock(DataPlaneProperties.class);
  private final ConfigMutationService service = new ConfigMutationService(repository, properties);

  @Test
  void upsertShouldAllowTomlFormat() {
    when(properties.maxConfigContentBytes()).thenReturn(1024);
    when(repository.mutate(org.mockito.ArgumentMatchers.any())).thenReturn(result("checkout-toml"));

    service.upsert(command("checkout-toml", "checkout.toml", "toml"));

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(repository).mutate(commandCaptor.capture());
    assertThat(commandCaptor.getValue().format()).isEqualTo("toml");
  }

  @Test
  void upsertShouldInferTextFormatFromTxtName() {
    when(properties.maxConfigContentBytes()).thenReturn(1024);
    when(repository.mutate(org.mockito.ArgumentMatchers.any())).thenReturn(result("notice-text"));

    service.upsert(command("notice-text", "notice.txt", null));

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(repository).mutate(commandCaptor.capture());
    assertThat(commandCaptor.getValue().format()).isEqualTo("text");
  }

  private ConfigMutationCommand command(String configId, String configName, String format) {
    return new ConfigMutationCommand(
        ConfigMutationAction.UPSERT,
        configId,
        configName,
        "APPLICATION",
        "acme.checkout",
        "app-config",
        "default",
        format,
        "FILE",
        false,
        "test config",
        "prod",
        "default",
        "default",
        "default",
        "INHERITABLE",
        "content",
        "xiaoy",
        "test");
  }

  private ConfigMutationResult result(String configId) {
    return new ConfigMutationResult(
        configId,
        1,
        "REL-1",
        1,
        1,
        "PUBLISHED",
        "sha256:test",
        OffsetDateTime.parse("2026-06-10T00:00:00Z"));
  }
}

package io.github.stellnula.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.application.ConfigMutationService;
import io.github.stellnula.application.FeatureFlagValidator;
import io.github.stellnula.domain.ConfigMutationCommand;
import io.github.stellnula.domain.ControlPlaneAppConfigRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class FeatureFlagControllerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConfigMutationService mutationService = mock(ConfigMutationService.class);
  private final FeatureFlagController controller =
      new FeatureFlagController(
          mutationService, new FeatureFlagValidator(objectMapper), objectMapper);

  @Test
  void listShouldIncludeAllFeatureFlagGroupsByDefault() {
    when(mutationService.findControlPlaneConfigs(
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class),
            nullable(String.class),
            anyString()))
        .thenReturn(
            List.of(
                record("PUBLISHED", true, "feature-flags"),
                record("PUBLISHED", true, "feature-flags.payment"),
                record("PUBLISHED", true, "default")));

    FeatureFlagController.FeatureFlagListResponse response =
        controller.list("acme.checkout", null, null, null, null);

    ArgumentCaptor<String> groupCaptor = ArgumentCaptor.forClass(String.class);
    verify(mutationService)
        .findControlPlaneConfigs(
            anyString(),
            anyString(),
            anyString(),
            nullable(String.class),
            nullable(String.class),
            groupCaptor.capture());
    assertThat(groupCaptor.getValue()).isEmpty();
    assertThat(response.records()).hasSize(2);
    assertThat(response.records())
        .extracting(FeatureFlagController.FeatureFlagResponse::group)
        .containsExactly("feature-flags", "feature-flags.payment");
  }

  @Test
  void saveDraftShouldMapFeatureFlagToAppConfigCommand() throws Exception {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("DRAFT", false, "feature-flags")));
    FeatureFlagController.FeatureFlagRequest request =
        new FeatureFlagController.FeatureFlagRequest(
            null,
            "acme.checkout",
            "checkout-new-flow",
            "checkout rollout",
            "prod",
            "default",
            null,
            "boolean",
            true,
            objectMapper.readTree("false"),
            objectMapper.readTree(
                """
                [
                  {
                    "name": "internal-users",
                    "conditions": [
                      {
                        "attribute": "labels.userType",
                        "op": "IN",
                        "values": ["internal"]
                      }
                    ],
                    "value": true
                  }
                ]
                """),
            null,
            null,
            null,
            "xiaoy",
            null);

    FeatureFlagController.FeatureFlagResponse response =
        controller.saveDraft("checkout-new-flow", request, null, null);

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(mutationService).saveDraft(commandCaptor.capture());
    ConfigMutationCommand command = commandCaptor.getValue();
    assertThat(command.configId()).isEqualTo("feature.checkout-new-flow");
    assertThat(command.configName()).isEqualTo("checkout-new-flow.json");
    assertThat(command.ownerType()).isEqualTo("APPLICATION");
    assertThat(command.ownerId()).isEqualTo("acme.checkout");
    assertThat(command.namespaceCode()).isEqualTo("app-config");
    assertThat(command.groupCode()).isEqualTo("feature-flags");
    assertThat(command.format()).isEqualTo("json");
    assertThat(command.contentType()).isEqualTo("FILE");
    assertThat(objectMapper.readTree(command.content()).path("key").asText())
        .isEqualTo("checkout-new-flow");
    assertThat(response.key()).isEqualTo("checkout-new-flow");
    assertThat(response.status()).isEqualTo("draft");
  }

  @Test
  void publishShouldSupportModuleFeatureFlagGroup() {
    when(mutationService.findControlPlaneConfig(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(record("PUBLISHED", true, "feature-flags.payment")));
    FeatureFlagController.FeatureFlagRequest request =
        new FeatureFlagController.FeatureFlagRequest(
            "feature.payment-risk-control",
            "acme.checkout",
            "payment-risk-control",
            "payment rollout",
            "prod",
            "default",
            "feature-flags.payment",
            "STRING",
            true,
            textNode("off"),
            objectMapper.createArrayNode(),
            null,
            null,
            null,
            "xiaoy",
            null);

    FeatureFlagController.FeatureFlagResponse response =
        controller.publish("payment-risk-control", request, null, null);

    ArgumentCaptor<ConfigMutationCommand> commandCaptor =
        ArgumentCaptor.forClass(ConfigMutationCommand.class);
    verify(mutationService).upsert(commandCaptor.capture());
    assertThat(commandCaptor.getValue().groupCode()).isEqualTo("feature-flags.payment");
    assertThat(response.status()).isEqualTo("published");
    assertThat(response.group()).isEqualTo("feature-flags.payment");
  }

  @Test
  void saveDraftShouldRejectInvalidFlagKey() {
    FeatureFlagController.FeatureFlagRequest request =
        new FeatureFlagController.FeatureFlagRequest(
            null,
            "acme.checkout",
            "checkout/new-flow",
            "checkout rollout",
            "prod",
            "default",
            null,
            "BOOLEAN",
            true,
            objectMapper.getNodeFactory().booleanNode(false),
            objectMapper.createArrayNode(),
            null,
            null,
            null,
            "xiaoy",
            null);

    assertThatThrownBy(() -> controller.saveDraft("checkout/new-flow", request, null, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  private JsonNode textNode(String value) {
    return objectMapper.getNodeFactory().textNode(value);
  }

  private ControlPlaneAppConfigRecord record(String status, boolean formatLocked, String group) {
    OffsetDateTime time = OffsetDateTime.parse("2026-06-10T00:00:00Z");
    String key =
        group.equals("feature-flags.payment") ? "payment-risk-control" : "checkout-new-flow";
    String type = group.equals("feature-flags.payment") ? "STRING" : "BOOLEAN";
    String defaultValue = group.equals("feature-flags.payment") ? "\"off\"" : "false";
    return new ControlPlaneAppConfigRecord(
        "feature." + key,
        "acme.checkout",
        key + ".json",
        "feature flag",
        "prod",
        "default",
        group,
        "json",
        2,
        status,
        """
        {
          "key": "%s",
          "type": "%s",
          "enabled": true,
          "defaultValue": %s,
          "rules": []
        }
        """
            .formatted(key, type, defaultValue),
        "xiaoy",
        time,
        formatLocked ? time : null,
        formatLocked);
  }
}

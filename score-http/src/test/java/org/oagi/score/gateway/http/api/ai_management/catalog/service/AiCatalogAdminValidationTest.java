package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiCatalogAdminValidationTest {

    private final ScoreUser actor = mock(ScoreUser.class);

    @Test
    void rejectsInvalidModelCommandsBeforeRequestingARepository() {
        AiModelCatalogAdminService service = new AiModelCatalogAdminService(
                mock(RepositoryFactory.class), mock(AiAdminPolicyService.class),
                new ObjectMapper());

        assertThatThrownBy(() -> service.create(actor,
                new AiModelCatalogUpdate(null, AiProviderId.from(1L), "gpt-5_6-sol",
                        true, false, -1,
                        4096, 128000L, 4096L, 100000L, 4096L, 16000L,
                        false, null, null, false, null, null,
                        true, false, true, false, List.of(), null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort order");
    }

    @Test
    void rejectsUnsupportedProviderTypesAndMalformedEndpoints() {
        AiProviderCatalogService service = new AiProviderCatalogService(
                mock(RepositoryFactory.class), mock(ApplicationSecretService.class),
                mock(AiAdminPolicyService.class), new ObjectMapper(),
                mock(AiProviderConnectionTester.class));

        assertThatThrownBy(() -> service.create(actor, new AiProviderUpdate(null,
                "provider", "unsupported", "https://example.test", null,
                null, null, true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> service.create(actor, new AiProviderUpdate(null,
                "provider", "openai", "not a URL", null,
                null, null, true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid AI provider base URL");
    }

    @Test
    void storedConnectionTestKeyIsRestrictedToThePersistedEndpoint() {
        var unchanged = new AiProviderUpdate(3L, "provider", "openai",
                "https://api.openai.com/v1", null, null, null, true, null);
        var changed = new AiProviderUpdate(3L, "provider", "openai",
                "https://attacker.example/v1", null, null, null, true, null);

        assertThat(AiProviderCatalogService.sameConnectionTarget("openai",
                "https://api.openai.com/v1", null, unchanged)).isTrue();
        assertThat(AiProviderCatalogService.sameConnectionTarget("openai",
                "https://api.openai.com/v1", null, changed)).isFalse();
    }
}

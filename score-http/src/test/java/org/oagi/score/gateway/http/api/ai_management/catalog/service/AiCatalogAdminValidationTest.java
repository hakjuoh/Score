package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiCatalogAdminValidationTest {

    private final ScoreUser actor = mock(ScoreUser.class);

    @Test
    void rejectsContextBudgetsThatCannotCreateARuntimeClient() {
        AiModelCatalogAdminService service = new AiModelCatalogAdminService(
                mock(DSLContext.class), mock(AiAdminPolicyService.class), new ObjectMapper());

        assertThatThrownBy(() -> service.create(actor, model(900L, 200L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must fit the context window");
    }

    @Test
    void rejectsUnsupportedProviderTypesAndMalformedEndpoints() {
        AiProviderCatalogService service = new AiProviderCatalogService(mock(DSLContext.class),
                mock(ApplicationSecretService.class), mock(AiAdminPolicyService.class),
                new ObjectMapper());

        assertThatThrownBy(() -> service.create(actor, new AiProviderUpdate(null,
                "provider", "unsupported", "https://example.test", null,
                null, null, true, null, "Validate provider type")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> service.create(actor, new AiProviderUpdate(null,
                "provider", "openai", "not a URL", null,
                null, null, true, null, "Validate provider URL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid AI provider base URL");
    }

    private AiModelCatalogUpdate model(Long reserve, Long threshold) {
        return new AiModelCatalogUpdate(null, 1L, "model", "deployment", "Model", "",
                true, false, 0, 4096, 1000L, reserve, threshold,
                200L, 100L, false, null, null, false, null, null,
                true, false, false, true, List.of(), null,
                List.of(new AiModelCatalogUpdate.ReasoningEffortUpdate(
                        "high", "High", "", true, 0)), "Validate model budget");
    }
}

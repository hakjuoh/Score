package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void providerUpdateKeepsReplacesAndClearsTheWriteOnlyKeyByPayloadState() {
        DSLContext dsl = mock(DSLContext.class);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        AiProviderCatalogService service = new AiProviderCatalogService(dsl, secrets,
                mock(AiAdminPolicyService.class), new ObjectMapper());
        ULong oldSecretId = ULong.valueOf(9);
        ULong newSecretId = ULong.valueOf(10);
        when(secrets.create(any(), anyString(), any(), any())).thenReturn(newSecretId);

        assertThat(service.updateSecret(dsl, oldSecretId, "provider", null, null))
                .isEqualTo(oldSecretId);
        assertThat(service.updateSecret(dsl, oldSecretId, "provider", "", null)).isNull();
        assertThat(service.updateSecret(dsl, null, "provider", "new-key", null))
                .isEqualTo(newSecretId);
        assertThat(service.updateSecret(dsl, oldSecretId, "provider", "replacement", null))
                .isEqualTo(oldSecretId);

        verify(secrets).create(any(), anyString(), any(), any());
        verify(secrets).replace(any(), org.mockito.ArgumentMatchers.eq(oldSecretId), any(), any());
        verify(secrets, never()).delete(any(), any());
    }

    @Test
    void providerUpdateAuditsCredentialChangesThroughTheUnifiedUpdateAction() {
        assertThat(AiProviderCatalogService.auditAction(null, true, true)).isEqualTo("UPDATE");
        assertThat(AiProviderCatalogService.auditAction(null, false, false)).isEqualTo("DISABLE");
        assertThat(AiProviderCatalogService.auditAction("replacement", true, true))
                .isEqualTo("ROTATE_KEY");
        assertThat(AiProviderCatalogService.auditAction("new-key", false, true))
                .isEqualTo("ROTATE_KEY");
        assertThat(AiProviderCatalogService.auditAction("", true, true)).isEqualTo("DELETE_KEY");
        assertThat(AiProviderCatalogService.auditAction("", false, true)).isEqualTo("UPDATE");
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

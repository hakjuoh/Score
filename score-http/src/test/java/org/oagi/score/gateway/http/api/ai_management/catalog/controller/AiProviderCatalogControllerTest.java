package org.oagi.score.gateway.http.api.ai_management.catalog.controller;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderApiKeyView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.service.AiProviderCatalogService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticatedPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiProviderCatalogControllerTest {

    @Test
    void preventsCredentialResponsesFromBeingCached() {
        AiProviderCatalogService providers = mock(AiProviderCatalogService.class);
        SessionService sessions = mock(SessionService.class);
        AuthenticatedPrincipal principal = mock(AuthenticatedPrincipal.class);
        ScoreUser actor = mock(ScoreUser.class);
        AiProviderId providerId = AiProviderId.from(7L);
        when(sessions.asScoreUser(principal)).thenReturn(actor);
        when(providers.revealApiKey(actor, providerId))
                .thenReturn(new AiProviderApiKeyView("secret-value", true));
        when(providers.maskedApiKey(actor, providerId))
                .thenReturn(new AiProviderApiKeyView("••••••••••••", false));
        AiProviderCatalogController controller =
                new AiProviderCatalogController(providers, sessions);

        var response = controller.revealApiKey(principal, providerId);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(new AiProviderApiKeyView("secret-value", true));

        var maskedResponse = controller.maskedApiKey(principal, providerId);
        assertThat(maskedResponse.getHeaders().getCacheControl()).contains("no-store");
        assertThat(maskedResponse.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(maskedResponse.getBody())
                .isEqualTo(new AiProviderApiKeyView("••••••••••••", false));
    }
}

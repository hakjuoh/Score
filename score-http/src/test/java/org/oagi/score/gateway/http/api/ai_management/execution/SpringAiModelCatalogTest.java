package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiModelCatalogTest {

    @Test
    void doesNotAdvertiseCapabilitiesMissingFromTheConfiguredRegistry() {
        ScoreAiModelRegistry registry = mock(ScoreAiModelRegistry.class);
        ScoreAiModelRegistry.ModelConfiguration configured =
                mock(ScoreAiModelRegistry.ModelConfiguration.class);
        ScoreAiModelRegistry.ContextBudgetDescriptor budget =
                mock(ScoreAiModelRegistry.ContextBudgetDescriptor.class);
        when(registry.resolveModelName("model")).thenReturn("model");
        when(registry.modelConfiguration("model")).thenReturn(configured);
        when(configured.name()).thenReturn("model");
        when(configured.providerType()).thenReturn("provider");
        when(configured.contextBudget()).thenReturn(budget);
        when(budget.contextWindow()).thenReturn(128_000L);
        when(budget.outputReserveTokens()).thenReturn(8_000L);

        AiModel model = new SpringAiModelCatalog(registry).require("model");

        assertThat(model.capabilities()).isEqualTo(AiModel.ModelCapabilities.TEXT_ONLY);
    }
}

package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinitionGeneratorAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.NameSuggesterAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelQueryServiceTest {

    @Test
    void publishesOnlyModelsInstalledInTheSharedExecutionCatalog() {
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.available()).thenReturn(List.of(
                model("fast-model", "openai"), model("deep-model", "anthropic")));
        AiModelQueryService service = new AiModelQueryService(
                mock(RepositoryFactory.class), mock(AgentExecutionService.class), models,
                mock(AgentOutputGuardrailChain.class),
                mock(DefinitionGeneratorAgent.class), mock(NameSuggesterAgent.class));

        assertThat(service.getAvailableModels())
                .containsExactly("fast-model", "deep-model");
    }

    private AiModel model(String id, String provider) {
        return new AiModel(new AiModel.ModelId(id), new AiModel.ProviderId(provider),
                AiModel.ModelCapabilities.TEXT_ONLY, AiModel.ContextWindow.UNKNOWN);
    }
}

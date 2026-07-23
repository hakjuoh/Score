package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinitionGeneratorAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.NameSuggesterAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    @Test
    void wrapsStandaloneModelGenerationInAnAiExecutionTurn() {
        AgentExecutionService execution = mock(AgentExecutionService.class);
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        AgentOutputGuardrailChain outputGuardrails = mock(AgentOutputGuardrailChain.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreAiObservability.Turn turn = mock(ScoreAiObservability.Turn.class);
        AiModel model = model("deep-model", "anthropic");
        when(models.require("deep-model")).thenReturn(model);
        AgentRunResult result = new AgentRunResult(new AiMessage.Assistant("generated"),
                List.of(), Optional.empty(), AgentRunResult.RunMetadata.empty());
        when(execution.execute(any())).thenReturn(result);
        AgentOutputGuardrailChain.Outcome guarded = mock(AgentOutputGuardrailChain.Outcome.class);
        when(guarded.allowed()).thenReturn(true);
        when(guarded.output()).thenReturn(new AiMessage.Assistant("generated"));
        when(guarded.decisions()).thenReturn(List.of());
        when(outputGuardrails.evaluate(any())).thenReturn(guarded);
        when(observability.startExecution(any(), any(), eq(0L), eq("parent"), eq("state")))
                .thenReturn(turn);
        AiModelQueryService service = new AiModelQueryService(
                mock(RepositoryFactory.class), execution, models, outputGuardrails,
                mock(DefinitionGeneratorAgent.class), mock(NameSuggesterAgent.class), observability);
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("definition-generator"),
                "Definition generator", "Generates a definition",
                new AgentDefinition.InstructionTemplate("Generate a definition."));

        assertThat(service.run(definition, mock(ScoreUser.class), "deep-model", "prompt",
                "definition_generation", "parent", "state")).isEqualTo("generated");

        verify(turn).executionStarted();
        verify(turn).complete("COMPLETED", null);
        verify(observability).recordGuardrails(any(), eq("agent_output"), eq(List.of()), eq(null));
    }

    private AiModel model(String id, String provider) {
        return new AiModel(new AiModel.ModelId(id), new AiModel.ProviderId(provider),
                AiModel.ModelCapabilities.TEXT_ONLY, AiModel.ContextWindow.UNKNOWN);
    }
}

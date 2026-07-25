package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinitionGeneratorAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.NameSuggesterAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
                mock(RepositoryFactory.class), mock(AgentRunner.class), models,
                mock(AgentOutputGuardrailChain.class),
                mock(DefinitionGeneratorAgent.class), mock(NameSuggesterAgent.class));

        assertThat(service.getAvailableModels())
                .containsExactly("fast-model", "deep-model");
    }

    @Test
    void wrapsStandaloneModelGenerationInAnAiExecutionTurn() {
        AgentRunner runner = mock(AgentRunner.class);
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        AgentOutputGuardrailChain outputGuardrails = new AgentOutputGuardrailChain(List.of(
                request -> new AgentOutputGuardrail.Result.Allow(request.candidate(),
                        GuardrailDecision.of("query-output", "1",
                                GuardrailDecision.Action.ALLOW))));
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreAiObservability.Turn turn = mock(ScoreAiObservability.Turn.class);
        AiModel model = model("deep-model", "anthropic");
        when(models.require("deep-model")).thenReturn(model);
        AgentRunResult result = new AgentRunResult(new AiMessage.Assistant("generated"),
                List.of(), Optional.empty(), AgentRunResult.RunMetadata.empty());
        when(runner.run(any(org.oagi.score.gateway.http.api.ai_management.agent.Agent.class),
                any(AgentWorkflowContext.class))).thenReturn(
                new AgentDecision.Complete(new AgentOutput(
                        "generated")));
        when(observability.startExecution(any(), any(), eq(0L), eq("parent"), eq("state")))
                .thenReturn(turn);
        AiModelQueryService service = new AiModelQueryService(
                mock(RepositoryFactory.class), runner, models, outputGuardrails,
                mock(DefinitionGeneratorAgent.class), mock(NameSuggesterAgent.class), observability);
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("definition-generator"),
                "Definition generator", "Generates a definition",
                new AgentDefinition.InstructionTemplate("Generate a definition."));

        assertThat(service.run(definition, mock(ScoreUser.class), "deep-model", "prompt",
                "definition_generation", "parent", "state")).isEqualTo("generated");

        verify(turn).executionStarted();
        verify(turn).complete("COMPLETED", null);
        verify(runner).run(any(org.oagi.score.gateway.http.api.ai_management.agent.Agent.class),
                any(AgentWorkflowContext.class));
    }

    @Test
    void rechecksInternalDiagnosticMetadataAtThePublicQueryBoundary() {
        AgentRunner runner = mock(AgentRunner.class);
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreAiObservability.Turn turn = mock(ScoreAiObservability.Turn.class);
        when(observability.startExecution(any(), any(), eq(0L),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(turn);
        when(runner.run(any(org.oagi.score.gateway.http.api.ai_management.agent.Agent.class),
                any(AgentWorkflowContext.class))).thenReturn(new AgentDecision.Complete(
                new AgentOutput("token=raw-secret", Map.of(
                        AgentRunner.OUTPUT_GUARDRAIL_APPLIED, true,
                        AgentRunner.OUTPUT_GUARDRAIL_SCOPE,
                        AgentOutputGuardrail.Scope.INTERNAL.name()))));
        AtomicInteger evaluations = new AtomicInteger();
        AgentOutputGuardrailChain outputGuardrails = new AgentOutputGuardrailChain(List.of(
                request -> {
                    evaluations.incrementAndGet();
                    assertThat(request.guardrailScope())
                            .isEqualTo(AgentOutputGuardrail.Scope.PUBLIC);
                    return new AgentOutputGuardrail.Result.Rewrite(
                            new AiMessage.Assistant("token=[REDACTED]"),
                            GuardrailDecision.of("query-redact", "1",
                                    GuardrailDecision.Action.REWRITE));
                }));
        AiModelQueryService service = new AiModelQueryService(
                mock(RepositoryFactory.class), runner, models, outputGuardrails,
                mock(DefinitionGeneratorAgent.class), mock(NameSuggesterAgent.class),
                observability);
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("name-suggester"),
                "Name suggester", "Suggests a name",
                new AgentDefinition.InstructionTemplate("Suggest a name."));

        assertThat(service.run(definition, mock(ScoreUser.class), "model", "prompt",
                "name_generation", null, null)).isEqualTo("token=[REDACTED]");
        assertThat(evaluations).hasValue(1);
    }

    private AiModel model(String id, String provider) {
        return new AiModel(new AiModel.ModelId(id), new AiModel.ProviderId(provider),
                AiModel.ModelCapabilities.TEXT_ONLY, AiModel.ContextWindow.UNKNOWN);
    }
}

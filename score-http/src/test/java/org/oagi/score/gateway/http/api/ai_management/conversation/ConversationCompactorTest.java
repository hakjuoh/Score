package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationCompactorTest {

    @Test
    void compactionIsAStandardNoToolAgentRunAndValidatesItsOutput() {
        AiModel model = new AiModel(new AiModel.ModelId("model"),
                new AiModel.ProviderId("provider"),
                AiModel.ModelCapabilities.TEXT_ONLY, AiModel.ContextWindow.UNKNOWN);
        AiModelCatalog models = mock(AiModelCatalog.class);
        when(models.require("model")).thenReturn(model);
        AtomicReference<org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation> invocation =
                new AtomicReference<>();
        AgentExecutionService execution = request -> {
            invocation.set(request);
            AiMessage.Assistant raw = new AiMessage.Assistant("api_key=raw-secret");
            return new AgentRunResult(raw, List.of(raw), Optional.empty(),
                    new AgentRunResult.RunMetadata(request.session().agent().id(),
                            model.id(), null, Map.of()));
        };
        AgentRunner runner = new AgentRunner(execution, models, null, null, List.of());
        AtomicReference<AgentOutputGuardrail.Scope> evaluatedScope = new AtomicReference<>();
        AgentOutputGuardrail redact = request -> {
            evaluatedScope.set(request.guardrailScope());
            return new AgentOutputGuardrail.Result.Rewrite(
                    new AiMessage.Assistant("api_key=[REDACTED]"),
                    GuardrailDecision.of("secret", "1", GuardrailDecision.Action.REWRITE));
        };
        ConversationCompactor compactor = new ConversationCompactor(runner,
                new AgentOutputGuardrailChain(List.of(redact)),
                new AiAgentCatalog(new DefaultResourceLoader()));
        ExecutionScope scope = new ExecutionScope("request", "conversation", "user", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());

        AgentOutput result = compactor.compact("model", List.of(new AiMessage.User("old")),
                new AiMessage.User("compact"), scope, AgentOutputGuardrail.Scope.PUBLIC);

        assertThat(result.content()).isEqualTo("api_key=[REDACTED]");
        assertThat(result.metadata())
                .containsEntry(AgentRunner.OUTPUT_GUARDRAIL_APPLIED, true)
                .containsEntry(AgentRunner.OUTPUT_GUARDRAIL_SCOPE, "PUBLIC");
        assertThat(evaluatedScope).hasValue(AgentOutputGuardrail.Scope.PUBLIC);
        assertThat(invocation.get().session().agent().id().value())
                .isEqualTo("compactor-agent");
        assertThat(invocation.get().session().tools().isEmpty()).isTrue();
        assertThat(invocation.get().scope().purpose())
                .isEqualTo(ExecutionScope.Purpose.COMPACTION);
    }
}

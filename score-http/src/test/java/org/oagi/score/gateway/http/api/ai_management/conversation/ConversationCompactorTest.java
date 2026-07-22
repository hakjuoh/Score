package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
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
        AtomicReference<AgentInvocation> captured = new AtomicReference<>();
        AgentExecutionService execution = invocation -> {
            captured.set(invocation);
            AiMessage.Assistant output = new AiMessage.Assistant("api_key=secret");
            return new AgentRunResult(output, List.of(output), Optional.empty(),
                    new AgentRunResult.RunMetadata(invocation.agent().id(),
                            invocation.agent().model().id(), null, Map.of()));
        };
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(new AiModel(new AiModel.ModelId("model"),
                new AiModel.ProviderId("provider"), null, null));
        AgentOutputGuardrail redact = request -> new AgentOutputGuardrail.Result.Rewrite(
                new AiMessage.Assistant("api_key=[REDACTED]"),
                GuardrailDecision.of("secret", "1", GuardrailDecision.Action.REWRITE));
        ConversationCompactor compactor = new ConversationCompactor(execution, models,
                new AgentOutputGuardrailChain(List.of(redact)),
                new AiAgentCatalog(new DefaultResourceLoader()));
        ExecutionScope scope = new ExecutionScope("request", "conversation", "user", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());

        String result = compactor.compact("model", List.of(new AiMessage.User("old")),
                new AiMessage.User("compact"), scope);

        assertThat(result).isEqualTo("api_key=[REDACTED]");
        assertThat(captured.get().agent().id().value()).isEqualTo("compactor-agent");
        assertThat(captured.get().agent().tools().isEmpty()).isTrue();
        assertThat(captured.get().scope().purpose()).isEqualTo(ExecutionScope.Purpose.COMPACTION);
    }
}

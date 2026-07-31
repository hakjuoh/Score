package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelInputGuardTest {

    private final ExecutionScope scope = new ExecutionScope(
            "request-1", "conversation-1", "user-1", 1,
            ExecutionScope.Purpose.USER_RESPONSE, List.of());

    @Test
    void rewritesOnlyTheLastUserMessageBeforeModelInvocation() {
        AgentInputGuardrail rewrite = request -> new AgentInputGuardrail.Result.Rewrite(
                new AiMessage.User("safe"), decision(GuardrailDecision.Action.REWRITE));
        AiModelInputGuard guard = new AiModelInputGuard(
                new AgentInputGuardrailChain(List.of(rewrite)), ScoreAiObservability.noop());
        List<Message> messages = List.of(
                new UserMessage("history"), new SystemMessage("policy"), new UserMessage("unsafe"));

        List<Message> guarded = guard.apply(request(), messages,
                guard.lastUserMessageIndex(messages), scope);

        assertThat(guarded).extracting(Message::getText)
                .containsExactly("history", "policy", "safe");
        assertThat(messages.get(2).getText()).isEqualTo("unsafe");
    }

    @Test
    void invalidGuardedIndexFailsClosedBeforeModelInvocation() {
        AgentInputGuardrail rewrite = request -> new AgentInputGuardrail.Result.Rewrite(
                new AiMessage.User("safe"), decision(GuardrailDecision.Action.REWRITE));
        AiModelInputGuard guard = new AiModelInputGuard(
                new AgentInputGuardrailChain(List.of(rewrite)), ScoreAiObservability.noop());
        List<Message> messages = List.of(new SystemMessage("policy"));

        assertThatThrownBy(() -> guard.apply(request(), messages, 99, scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("guardedUserIndex must identify a user message");
        assertThatThrownBy(() -> guard.apply(request(), messages, 0, scope))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(guard.lastUserMessageIndex(messages)).isEqualTo(-1);
    }

    @Test
    void missingUserMessageIsGuardedAsEmptyInputWithoutChangingMessages() {
        AgentInputGuardrail allow = request -> {
            assertThat(request.input().content()).isEmpty();
            return new AgentInputGuardrail.Result.Allow(
                    decision(GuardrailDecision.Action.ALLOW));
        };
        AiModelInputGuard guard = new AiModelInputGuard(
                new AgentInputGuardrailChain(List.of(allow)), ScoreAiObservability.noop());
        List<Message> messages = List.of(new SystemMessage("policy"));

        assertThat(guard.apply(request(), messages, -1, scope)).isSameAs(messages);
    }

    @Test
    void refusesTheModelCallWithoutReturningGuardedContent() {
        GuardrailRefusal refusal = new GuardrailRefusal(
                decision(GuardrailDecision.Action.REFUSE), "BLOCKED", "ai.policy.refused");
        AgentInputGuardrail refuse = request -> new AgentInputGuardrail.Result.Refuse(refusal);
        AiModelInputGuard guard = new AiModelInputGuard(
                new AgentInputGuardrailChain(List.of(refuse)), ScoreAiObservability.noop());
        List<Message> messages = List.of(new UserMessage("secret"));

        assertThatThrownBy(() -> guard.apply(request(), messages, 0, scope))
                .isInstanceOf(AgentInputRefusedException.class);
    }

    private ChatRequest request() {
        return new ChatRequest("prompt", "request-1", null, "conversation-1",
                null, List.of(), null, "model-1", null, null);
    }

    private GuardrailDecision decision(GuardrailDecision.Action action) {
        return GuardrailDecision.of("test-policy", "1", action);
    }
}

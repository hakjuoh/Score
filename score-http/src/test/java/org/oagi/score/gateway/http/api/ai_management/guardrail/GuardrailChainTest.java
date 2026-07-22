package org.oagi.score.gateway.http.api.ai_management.guardrail;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailChainTest {

    private final ExecutionScope scope = new ExecutionScope("request", "conversation", "user",
            1, ExecutionScope.Purpose.USER_RESPONSE, List.of());

    @Test
    void inputRewriteFeedsTheNextPolicy() {
        AtomicBoolean safeValueObserved = new AtomicBoolean();
        AgentInputGuardrail rewrite = request -> new AgentInputGuardrail.Result.Rewrite(
                new AiMessage.User("safe"), decision(GuardrailDecision.Action.REWRITE));
        AgentInputGuardrail verify = request -> {
            safeValueObserved.set("safe".equals(request.input().content()));
            return new AgentInputGuardrail.Result.Allow(decision(GuardrailDecision.Action.ALLOW));
        };

        var result = new AgentInputGuardrailChain(List.of(rewrite, verify)).evaluate(
                new AgentInputGuardrail.Request(AgentInputGuardrail.Scope.TURN_LOCAL,
                        new AiMessage.User("unsafe"), List.of(), scope, Map.of()));

        assertThat(result.allowed()).isTrue();
        assertThat(result.input().content()).isEqualTo("safe");
        assertThat(safeValueObserved).isTrue();
    }

    @Test
    void inputRewriteUpdatesTheAssembledViewForEveryLaterPolicy() {
        AiMessage.User unsafe = new AiMessage.User("unsafe");
        AgentInputGuardrail rewrite = request -> new AgentInputGuardrail.Result.Rewrite(
                new AiMessage.User("safe"), decision(GuardrailDecision.Action.REWRITE));
        AgentInputGuardrail verify = request -> {
            assertThat(request.input().content()).isEqualTo("safe");
            assertThat(request.assembledMessages()).extracting(AiMessage::content)
                    .containsExactly("system", "safe");
            return new AgentInputGuardrail.Result.Allow(
                    decision(GuardrailDecision.Action.ALLOW));
        };

        var result = new AgentInputGuardrailChain(List.of(rewrite, verify)).evaluate(
                new AgentInputGuardrail.Request(AgentInputGuardrail.Scope.MODEL,
                        unsafe, List.of(new AiMessage.System("system"), unsafe),
                        scope, Map.of()));

        assertThat(result.allowed()).isTrue();
        assertThat(result.input().content()).isEqualTo("safe");
    }

    @Test
    void unavailableRequiredInputPolicyFailsClosed() {
        AgentInputGuardrail unavailable = request -> { throw new IllegalStateException("offline"); };
        var result = new AgentInputGuardrailChain(List.of(unavailable)).evaluate(
                new AgentInputGuardrail.Request(AgentInputGuardrail.Scope.TURN_LOCAL,
                        new AiMessage.User("value"), List.of(), scope, Map.of()));

        assertThat(result.allowed()).isFalse();
        assertThat(result.refusal().publicMessageKey()).isEqualTo("ai.policy.unavailable");
    }

    @Test
    void outputRefusalNeverReturnsTheCandidate() {
        AgentOutputGuardrail refuse = request -> new AgentOutputGuardrail.Result.Refuse(
                new GuardrailRefusal(decision(GuardrailDecision.Action.REFUSE),
                        "BLOCKED", "ai.policy.refused"));
        var result = new AgentOutputGuardrailChain(List.of(refuse)).evaluate(
                new AgentOutputGuardrail.Request(AgentOutputGuardrail.Scope.PUBLIC,
                        new AiMessage.Assistant("raw secret"), scope, Map.of()));

        assertThat(result.allowed()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.refusal()).isNotNull();
    }

    @Test
    void requiredChainsCannotBeEmpty() {
        assertThatThrownBy(() -> new AgentInputGuardrailChain(List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AgentOutputGuardrailChain(List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private GuardrailDecision decision(GuardrailDecision.Action action) {
        return GuardrailDecision.of("test-policy", "1", action);
    }
}

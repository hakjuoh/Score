package org.oagi.score.gateway.http.api.ai_management.guardrail;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic fail-closed input chain. A rewrite is passed to every later policy. */
public final class AgentInputGuardrailChain {

    private final List<AgentInputGuardrail> guardrails;

    public AgentInputGuardrailChain(List<AgentInputGuardrail> guardrails) {
        this.guardrails = guardrails != null
                ? guardrails.stream().filter(Objects::nonNull).toList() : List.of();
        if (this.guardrails.isEmpty()) {
            throw new IllegalStateException("At least one required Agent input Guardrail is required.");
        }
    }

    public Outcome evaluate(AgentInputGuardrail.Request request) {
        AiMessage.User current = request.input();
        List<AiMessage> assembled = new ArrayList<>(request.assembledMessages());
        int inputIndex = identityIndex(assembled, current);
        List<GuardrailDecision> decisions = new ArrayList<>();
        for (AgentInputGuardrail guardrail : guardrails) {
            AgentInputGuardrail.Result result;
            try {
                result = Objects.requireNonNull(guardrail.evaluate(new AgentInputGuardrail.Request(
                        request.guardrailScope(), current, assembled,
                        request.executionScope(), request.policyContext())), "Guardrail result");
            } catch (RuntimeException unavailable) {
                return Outcome.refused(unavailableRefusal(), decisions);
            }
            decisions.add(result.decision());
            if (result instanceof AgentInputGuardrail.Result.Rewrite rewrite) {
                current = rewrite.safeInput();
                if (inputIndex >= 0) assembled.set(inputIndex, current);
            }
            if (result instanceof AgentInputGuardrail.Result.Refuse refuse) {
                return Outcome.refused(refuse.refusal(), decisions);
            }
        }
        return Outcome.allowed(current, decisions);
    }

    private int identityIndex(List<AiMessage> messages, AiMessage.User input) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) == input) return index;
        }
        return -1;
    }

    private GuardrailRefusal unavailableRefusal() {
        return new GuardrailRefusal(GuardrailDecision.of("required-input-chain", "1",
                GuardrailDecision.Action.REFUSE), "POLICY_UNAVAILABLE", "ai.policy.unavailable");
    }

    public record Outcome(AiMessage.User input, List<GuardrailDecision> decisions,
                          GuardrailRefusal refusal) {
        public Outcome {
            decisions = decisions != null ? List.copyOf(decisions) : List.of();
        }
        public static Outcome allowed(AiMessage.User input, List<GuardrailDecision> decisions) {
            return new Outcome(Objects.requireNonNull(input), decisions, null);
        }
        public static Outcome refused(GuardrailRefusal refusal, List<GuardrailDecision> decisions) {
            return new Outcome(null, decisions, Objects.requireNonNull(refusal));
        }
        public boolean allowed() { return refusal == null; }
    }
}

package org.oagi.score.gateway.http.api.ai_management.guardrail;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Buffers and validates a complete candidate before any caller may disclose it. */
public final class AgentOutputGuardrailChain {

    private final List<AgentOutputGuardrail> guardrails;

    public AgentOutputGuardrailChain(List<AgentOutputGuardrail> guardrails) {
        this.guardrails = guardrails != null
                ? guardrails.stream().filter(Objects::nonNull).toList() : List.of();
        if (this.guardrails.isEmpty()) {
            throw new IllegalStateException("At least one required Agent output Guardrail is required.");
        }
    }

    public Outcome evaluate(AgentOutputGuardrail.Request request) {
        AiMessage.Assistant current = request.candidate();
        List<GuardrailDecision> decisions = new ArrayList<>();
        for (AgentOutputGuardrail guardrail : guardrails) {
            AgentOutputGuardrail.Result result;
            try {
                result = Objects.requireNonNull(guardrail.evaluate(new AgentOutputGuardrail.Request(
                        request.guardrailScope(), current, request.executionScope(), request.evidence())));
            } catch (RuntimeException unavailable) {
                return Outcome.refused(unavailableRefusal(), decisions);
            }
            if (result instanceof AgentOutputGuardrail.Result.Allow allow) {
                decisions.add(allow.decision()); current = allow.output();
            } else if (result instanceof AgentOutputGuardrail.Result.Rewrite rewrite) {
                decisions.add(rewrite.decision()); current = rewrite.safeOutput();
            } else if (result instanceof AgentOutputGuardrail.Result.Retry retry) {
                decisions.add(retry.decision()); return Outcome.retry(retry.safeFeedback(), decisions);
            } else if (result instanceof AgentOutputGuardrail.Result.Refuse refuse) {
                decisions.add(refuse.refusal().decision()); return Outcome.refused(refuse.refusal(), decisions);
            }
        }
        return Outcome.allowed(current, decisions);
    }

    private GuardrailRefusal unavailableRefusal() {
        return new GuardrailRefusal(GuardrailDecision.of("required-output-chain", "1",
                GuardrailDecision.Action.REFUSE), "POLICY_UNAVAILABLE", "ai.policy.unavailable");
    }

    public record Outcome(AiMessage.Assistant output, String retryFeedback,
                          List<GuardrailDecision> decisions, GuardrailRefusal refusal) {
        public Outcome { decisions = decisions != null ? List.copyOf(decisions) : List.of(); }
        static Outcome allowed(AiMessage.Assistant output, List<GuardrailDecision> decisions) {
            return new Outcome(output, null, decisions, null);
        }
        static Outcome retry(String feedback, List<GuardrailDecision> decisions) {
            return new Outcome(null, feedback, decisions, null);
        }
        static Outcome refused(GuardrailRefusal refusal, List<GuardrailDecision> decisions) {
            return new Outcome(null, null, decisions, refusal);
        }
        public boolean allowed() { return output != null; }
        public boolean retryRequested() { return retryFeedback != null; }
    }
}

package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;

import java.util.List;
import java.util.Map;

/** Reusable adapters for declaring application output policy on an Agent. */
public final class AgentGuardrailHandlers {

    private AgentGuardrailHandlers() {
    }

    /**
     * Adapts the shared output chain to the definition-owned guardrail contract.
     * This keeps policy conversion and observation identical for standalone
     * model features such as compaction and model-assisted generation.
     */
    public static AgentOutputGuardrail output(AgentOutputGuardrailChain chain,
                                               ScoreAiObservability observability,
                                               AgentOutputGuardrail.Scope scope,
                                               String observationType,
                                               Map<String, Object> evidence) {
        return request -> {
            AgentOutputGuardrailChain.Outcome outcome = chain.evaluate(
                    new AgentOutputGuardrail.Request(scope, request.candidate(),
                            request.executionScope(), evidence));
            observability.recordGuardrails(request.executionScope().requestId(),
                    observationType, outcome.decisions(), outcome.refusal());
            if (outcome.retryRequested()) {
                return new AgentOutputGuardrail.Result.Retry(outcome.retryFeedback(),
                        lastDecision(outcome.decisions(), GuardrailDecision.Action.RETRY,
                                observationType));
            }
            if (!outcome.allowed()) {
                return new AgentOutputGuardrail.Result.Refuse(outcome.refusal());
            }
            GuardrailDecision.Action action = outcome.output() == request.candidate()
                    ? GuardrailDecision.Action.ALLOW : GuardrailDecision.Action.REWRITE;
            GuardrailDecision decision = lastDecision(outcome.decisions(), action,
                    observationType);
            return action == GuardrailDecision.Action.ALLOW
                    ? new AgentOutputGuardrail.Result.Allow(outcome.output(), decision)
                    : new AgentOutputGuardrail.Result.Rewrite(outcome.output(), decision);
        };
    }

    /** Builds the public output policy declared by a user-visible Agent. */
    public static AgentGuardrails publicOutput(AgentOutputGuardrailChain chain,
                                                ScoreAiObservability observability,
                                                String observationType,
                                                Map<String, Object> evidence) {
        if (chain == null) return AgentGuardrails.none();
        return new AgentGuardrails(List.of(), List.of(output(chain,
                observability != null ? observability : ScoreAiObservability.noop(),
                AgentOutputGuardrail.Scope.PUBLIC, observationType, evidence)),
                AgentOutputGuardrail.Scope.PUBLIC);
    }

    private static GuardrailDecision lastDecision(List<GuardrailDecision> decisions,
                                                   GuardrailDecision.Action action,
                                                   String prefix) {
        return decisions.stream().filter(decision -> decision.action() == action)
                .reduce((first, second) -> second)
                .orElseGet(() -> GuardrailDecision.of(prefix + "-output", "1", action));
    }
}

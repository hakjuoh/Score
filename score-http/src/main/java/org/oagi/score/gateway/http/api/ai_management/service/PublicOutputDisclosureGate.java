package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutputRetryLimitException;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The one final trust boundary for content leaving an Agent execution. */
final class PublicOutputDisclosureGate {

    private final AgentOutputGuardrailChain guardrails;
    private final ScoreAiObservability observability;

    PublicOutputDisclosureGate(AgentOutputGuardrailChain guardrails,
                               ScoreAiObservability observability) {
        this.guardrails = guardrails;
        this.observability = observability != null
                ? observability : ScoreAiObservability.noop();
    }

    Outcome evaluate(AgentOutput candidate, ExecutionScope scope,
                     Map<String, Object> context) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(scope, "scope");
        if (candidate.passedOutputGuardrail(AgentOutputGuardrail.Scope.PUBLIC)
                || guardrails == null) {
            return Outcome.allowed(candidate.content());
        }
        AgentOutputGuardrailChain.Outcome outcome = Objects.requireNonNull(
                guardrails.evaluate(new AgentOutputGuardrail.Request(
                        AgentOutputGuardrail.Scope.PUBLIC,
                        new AiMessage.Assistant(candidate.content()), scope,
                        context != null ? Map.copyOf(context) : Map.of())),
                "Public output guardrail outcome");
        observability.recordGuardrails(scope.requestId(), "agent_output",
                outcome.decisions(), outcome.refusal());
        return new Outcome(
                outcome.output() != null ? outcome.output().content() : null,
                outcome.retryFeedback(), outcome.refusal(), outcome.decisions());
    }

    String require(AgentOutput candidate, ExecutionScope scope,
                   Map<String, Object> context, Agent.AgentId agentId) {
        Outcome outcome = evaluate(candidate, scope, context);
        if (outcome.refusal() != null) {
            throw new AgentGuardrailRefusedException(outcome.refusal(), agentId);
        }
        if (outcome.retryRequested()) {
            throw new AgentOutputRetryLimitException(agentId, outcome.retryFeedback());
        }
        return Objects.requireNonNull(outcome.output(), "Public output policy result");
    }

    record Outcome(String output, String retryFeedback,
                   GuardrailRefusal refusal,
                   List<GuardrailDecision> decisions) {
        Outcome {
            decisions = decisions != null ? List.copyOf(decisions) : List.of();
        }

        static Outcome allowed(String output) {
            return new Outcome(output, null, null, List.of());
        }

        boolean retryRequested() {
            return retryFeedback != null;
        }
    }
}

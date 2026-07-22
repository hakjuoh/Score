package org.oagi.score.gateway.http.api.ai_management.guardrail;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.util.Map;
import java.util.Objects;

@FunctionalInterface
public interface AgentOutputGuardrail {

    Result evaluate(Request request);

    record Request(Scope guardrailScope, AiMessage.Assistant candidate,
                   ExecutionScope executionScope, Map<String, Object> evidence) {
        public Request {
            Objects.requireNonNull(guardrailScope, "guardrailScope");
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(executionScope, "executionScope");
            evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
        }
    }

    enum Scope { INTERNAL, PUBLIC }

    sealed interface Result permits Result.Allow, Result.Rewrite, Result.Retry, Result.Refuse {
        record Allow(AiMessage.Assistant output, GuardrailDecision decision) implements Result {
            public Allow { Objects.requireNonNull(output); require(decision, GuardrailDecision.Action.ALLOW); }
        }
        record Rewrite(AiMessage.Assistant safeOutput, GuardrailDecision decision) implements Result {
            public Rewrite { Objects.requireNonNull(safeOutput); require(decision, GuardrailDecision.Action.REWRITE); }
        }
        record Retry(String safeFeedback, GuardrailDecision decision) implements Result {
            public Retry {
                safeFeedback = Objects.requireNonNullElse(safeFeedback, "Regenerate a safe response.");
                require(decision, GuardrailDecision.Action.RETRY);
            }
        }
        record Refuse(GuardrailRefusal refusal) implements Result {
            public Refuse { Objects.requireNonNull(refusal); }
        }
    }

    private static void require(GuardrailDecision decision, GuardrailDecision.Action action) {
        Objects.requireNonNull(decision, "decision");
        if (decision.action() != action) throw new IllegalArgumentException("Guardrail action mismatch.");
    }
}

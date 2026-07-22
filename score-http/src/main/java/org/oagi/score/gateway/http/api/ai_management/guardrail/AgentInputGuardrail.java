package org.oagi.score.gateway.http.api.ai_management.guardrail;

import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@FunctionalInterface
public interface AgentInputGuardrail {

    Result evaluate(Request request);

    record Request(Scope guardrailScope, AiMessage.User input, List<AiMessage> assembledMessages,
                   ExecutionScope executionScope, Map<String, Object> policyContext) {
        public Request {
            Objects.requireNonNull(guardrailScope, "guardrailScope");
            Objects.requireNonNull(input, "input");
            assembledMessages = assembledMessages != null ? List.copyOf(assembledMessages) : List.of();
            Objects.requireNonNull(executionScope, "executionScope");
            policyContext = policyContext != null ? Map.copyOf(policyContext) : Map.of();
        }
    }

    enum Scope { TURN_LOCAL, TURN_MODEL_ASSISTED, MODEL }

    sealed interface Result permits Result.Allow, Result.Rewrite, Result.Refuse {
        GuardrailDecision decision();
        record Allow(GuardrailDecision decision) implements Result {
            public Allow { require(decision, GuardrailDecision.Action.ALLOW); }
        }
        record Rewrite(AiMessage.User safeInput, GuardrailDecision decision) implements Result {
            public Rewrite {
                Objects.requireNonNull(safeInput, "safeInput");
                require(decision, GuardrailDecision.Action.REWRITE);
            }
        }
        record Refuse(GuardrailRefusal refusal) implements Result {
            public Refuse { Objects.requireNonNull(refusal, "refusal"); }
            @Override public GuardrailDecision decision() { return refusal.decision(); }
        }
    }

    private static void require(GuardrailDecision decision, GuardrailDecision.Action action) {
        Objects.requireNonNull(decision, "decision");
        if (decision.action() != action) throw new IllegalArgumentException("Guardrail action mismatch.");
    }
}

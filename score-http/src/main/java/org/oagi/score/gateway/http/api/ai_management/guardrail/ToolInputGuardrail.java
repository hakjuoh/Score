package org.oagi.score.gateway.http.api.ai_management.guardrail;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;

import java.util.Objects;

@FunctionalInterface
public interface ToolInputGuardrail {

    Result evaluate(Request request);

    record Request(Stage stage, AiTool.ToolSpecification tool, AiTool.ToolArguments arguments,
                   ExecutionScope scope) {
        public Request {
            Objects.requireNonNull(stage); Objects.requireNonNull(tool);
            Objects.requireNonNull(arguments); Objects.requireNonNull(scope);
        }
    }

    enum Stage { PRE_AUTHORIZATION, PRE_EXECUTION }

    sealed interface Result permits Result.Allow, Result.Rewrite, Result.Refuse {
        record Allow(AiTool.ToolArguments arguments, GuardrailDecision decision) implements Result {
            public Allow { Objects.requireNonNull(arguments); require(decision, GuardrailDecision.Action.ALLOW); }
        }
        record Rewrite(AiTool.ToolArguments safeArguments, GuardrailDecision decision) implements Result {
            public Rewrite { Objects.requireNonNull(safeArguments); require(decision, GuardrailDecision.Action.REWRITE); }
        }
        record Refuse(AiTool.ToolResult replacement, GuardrailDecision decision) implements Result {
            public Refuse { Objects.requireNonNull(replacement); require(decision, GuardrailDecision.Action.REFUSE); }
        }
    }

    private static void require(GuardrailDecision decision, GuardrailDecision.Action action) {
        Objects.requireNonNull(decision);
        if (decision.action() != action) throw new IllegalArgumentException("Guardrail action mismatch.");
    }
}

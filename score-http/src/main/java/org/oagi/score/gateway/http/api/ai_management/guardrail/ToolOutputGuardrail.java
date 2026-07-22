package org.oagi.score.gateway.http.api.ai_management.guardrail;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;

import java.util.Objects;

@FunctionalInterface
public interface ToolOutputGuardrail {

    Result evaluate(Request request);

    record Request(AiTool.ToolSpecification tool, AiTool.ToolArguments arguments,
                   AiTool.ToolResult output, ExecutionScope scope) {
        public Request {
            Objects.requireNonNull(tool); Objects.requireNonNull(arguments);
            Objects.requireNonNull(output); Objects.requireNonNull(scope);
        }
    }

    sealed interface Result permits Result.Allow, Result.Rewrite, Result.Refuse {
        record Allow(AiTool.ToolResult output, GuardrailDecision decision) implements Result {
            public Allow { Objects.requireNonNull(output); require(decision, GuardrailDecision.Action.ALLOW); }
        }
        record Rewrite(AiTool.ToolResult safeOutput, GuardrailDecision decision) implements Result {
            public Rewrite { Objects.requireNonNull(safeOutput); require(decision, GuardrailDecision.Action.REWRITE); }
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

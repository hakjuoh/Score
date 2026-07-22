package org.oagi.score.gateway.http.api.ai_management.guardrail;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves mandatory baseline plus Tool-specific Guardrails for every core Tool. */
public final class ToolGuardrailRegistry {

    private final Set baseline;
    private final Map<AiTool.ToolId, Set> registrations;

    public ToolGuardrailRegistry(Set baseline, Map<AiTool.ToolId, Set> registrations) {
        this.baseline = Objects.requireNonNull(baseline, "baseline Guardrails");
        if (baseline.input().isEmpty() || baseline.output().isEmpty()) {
            throw new IllegalStateException("Tool Guardrail baseline must contain both directions.");
        }
        this.registrations = registrations != null ? Map.copyOf(registrations) : Map.of();
    }

    public Set resolve(AiTool.ToolId toolId, ExecutionScope scope) {
        Set specific = registrations.get(toolId);
        if (specific == null) return baseline;
        List<ToolInputGuardrail> input = new ArrayList<>(baseline.input()); input.addAll(specific.input());
        List<ToolOutputGuardrail> output = new ArrayList<>(baseline.output()); output.addAll(specific.output());
        return new Set(input, output);
    }

    public record Set(List<ToolInputGuardrail> input, List<ToolOutputGuardrail> output) {
        public Set {
            input = input != null ? input.stream().filter(Objects::nonNull).toList() : List.of();
            output = output != null ? output.stream().filter(Objects::nonNull).toList() : List.of();
        }
    }
}

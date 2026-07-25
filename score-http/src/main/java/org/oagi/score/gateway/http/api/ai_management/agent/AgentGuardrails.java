package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;

import java.util.List;
import java.util.Objects;

/** Guardrails declared by one Agent and run by the shared AgentRunner. */
public record AgentGuardrails(List<AgentInputGuardrail> input,
                              List<AgentOutputGuardrail> output,
                              AgentOutputGuardrail.Scope outputScope) {

    public AgentGuardrails(List<AgentInputGuardrail> input,
                           List<AgentOutputGuardrail> output) {
        this(input, output, AgentOutputGuardrail.Scope.INTERNAL);
    }

    public AgentGuardrails {
        input = copy(input);
        output = copy(output);
        outputScope = outputScope != null
                ? outputScope : AgentOutputGuardrail.Scope.INTERNAL;
    }

    public static AgentGuardrails none() {
        return new AgentGuardrails(List.of(), List.of(),
                AgentOutputGuardrail.Scope.INTERNAL);
    }

    public boolean hasInput() {
        return !input.isEmpty();
    }

    public boolean hasOutput() {
        return !output.isEmpty();
    }

    private static <T> List<T> copy(List<T> values) {
        return values != null ? values.stream().filter(Objects::nonNull).toList() : List.of();
    }
}

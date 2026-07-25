package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Objects;
import java.util.ArrayList;

/** Request-scoped definition produced from a Planner assignment. */
public final class AssignedAgent implements Agent {

    private final AgentDefinition definition;

    public AssignedAgent(AgentDefinition base, AgentRequestHandler requestHandler,
                         AgentResponseHandler responseHandler) {
        this(base, requestHandler, responseHandler, null);
    }

    public AssignedAgent(AgentDefinition base, AgentRequestHandler requestHandler,
                         AgentResponseHandler responseHandler, AgentGuardrails guardrails) {
        Objects.requireNonNull(base, "definition");
        this.definition = new AgentDefinition(base.id(), base.name(), base.description(),
                base.instruction(), Objects.requireNonNull(requestHandler, "requestHandler"),
                AgentToolHandler.transport(), Objects.requireNonNull(responseHandler, "responseHandler"),
                append(base.guardrails(), guardrails), true);
    }

    @Override
    public AgentDefinition definition() {
        return definition;
    }

    private static AgentGuardrails append(AgentGuardrails base, AgentGuardrails additional) {
        AgentGuardrails original = base != null ? base : AgentGuardrails.none();
        if (additional == null || additional.output().isEmpty()) return original;
        ArrayList<org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail> output =
                new ArrayList<>(original.output());
        output.addAll(additional.output());
        var scope = original.outputScope()
                == org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail.Scope.PUBLIC
                || additional.outputScope()
                == org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail.Scope.PUBLIC
                ? org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail.Scope.PUBLIC
                : org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail.Scope.INTERNAL;
        return new AgentGuardrails(original.input(), output, scope);
    }
}

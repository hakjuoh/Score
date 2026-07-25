package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Objects;

/** Failure supplied to an Agent-defined response policy. */
public record AgentFailure(Agent agent, AgentWorkflowContext workflow,
                           RuntimeException exception) {
    public AgentFailure {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(exception, "exception");
    }
}

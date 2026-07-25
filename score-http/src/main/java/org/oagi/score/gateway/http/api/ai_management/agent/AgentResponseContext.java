package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Objects;

/** Context supplied to an Agent's response handler after the shared run completes. */
public record AgentResponseContext(Agent agent, AgentWorkflowContext workflow,
                                   AgentRunResult result) {
    public AgentResponseContext {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(result, "result");
    }
}

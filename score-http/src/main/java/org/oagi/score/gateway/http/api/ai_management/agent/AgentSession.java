package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;

import java.util.Objects;

/**
 * Request-scoped execution binding for an Agent definition.
 *
 * <p>The binding is deliberately not an Agent. Model, instruction rendering,
 * and request-authorized tools belong to this session and are supplied by the
 * runner/execution boundary.</p>
 */
public record AgentSession(Agent agent, AiModel model, Agent.Instruction instruction,
                           ToolSet tools) {

    public AgentSession {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(instruction, "instruction");
        tools = tools != null ? tools : ToolSet.empty();
    }
}

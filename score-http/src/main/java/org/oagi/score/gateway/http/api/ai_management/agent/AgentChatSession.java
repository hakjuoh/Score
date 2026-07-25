package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Objects;

/**
 * Runner-bound Chat session descriptor.
 *
 * <p>The definition prepares a neutral Chat context; the shared Runner binds
 * the addressed Agent, model identity, and final Tool binding before this
 * descriptor crosses into a provider adapter.</p>
 */
public record AgentChatSession(Agent agent, String modelName, Agent.Instruction instruction,
                               AgentExecutionContext context,
                               AgentToolBinding tools) {

    public AgentChatSession {
        Objects.requireNonNull(agent, "agent");
        modelName = Objects.requireNonNull(modelName, "modelName").strip();
        if (modelName.isEmpty()) throw new IllegalArgumentException("modelName is required");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tools, "tools");
    }
}

package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;

/** Binds a reusable definition to one model and one request-authorized ToolSet. */
@FunctionalInterface
public interface AgentFactory {

    AgentSession create(Agent agent, AiModel model, ToolSet availableTools);

    default AgentSession create(AgentDefinition definition, AiModel model,
                                ToolSet availableTools) {
        return create(new DefinedAgent(definition), model, availableTools);
    }

    static AgentFactory binding() {
        return (agent, model, availableTools) -> new AgentSession(
                agent, model, agent.definition().instruction().render(),
                availableTools != null ? availableTools : ToolSet.empty());
    }
}

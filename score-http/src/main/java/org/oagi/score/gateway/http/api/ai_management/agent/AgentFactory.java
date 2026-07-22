package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;

/** Binds a reusable definition to one model and one request-authorized ToolSet. */
@FunctionalInterface
public interface AgentFactory {

    ResolvedAgent create(AgentDefinition definition, AiModel model, ToolSet availableTools);

    static AgentFactory binding() {
        return (definition, model, availableTools) -> new ResolvedAgent(
                definition, model, definition.instruction().render(),
                availableTools != null ? availableTools : ToolSet.empty());
    }
}

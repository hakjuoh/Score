package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;

import java.util.Objects;

/** Fully resolved execution subject: Model + Instruction + Tool Set. */
public record ResolvedAgent(AgentDefinition definition, AiModel model, Instruction instruction,
                            ToolSet tools) implements Agent {

    public ResolvedAgent {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(instruction, "instruction");
        tools = tools != null ? tools : ToolSet.empty();
    }
}

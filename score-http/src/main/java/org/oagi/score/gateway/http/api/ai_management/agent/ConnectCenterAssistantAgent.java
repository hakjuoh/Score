package org.oagi.score.gateway.http.api.ai_management.agent;

import org.springframework.stereotype.Component;

import java.util.Map;

/** User-facing root Agent backed by the configured ROOT definition in the common catalog. */
@Component("connectcenter-assistant")
public final class ConnectCenterAssistantAgent implements Agent {

    private final AiAgentCatalog agents;

    public ConnectCenterAssistantAgent(AiAgentCatalog agents) {
        this.agents = agents;
    }

    @Override
    public AgentDefinition definition() {
        return agents.configuredRootDefinition();
    }

    public Instruction instruction(Map<String, ?> parameters) {
        return definition().instruction().render(parameters);
    }
}

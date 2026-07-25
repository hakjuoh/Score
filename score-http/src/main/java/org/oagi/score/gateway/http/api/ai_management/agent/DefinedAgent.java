package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Objects;

/** Immutable Agent value used when a definition is not backed by a Spring bean. */
public record DefinedAgent(AgentDefinition definition) implements Agent {

    public DefinedAgent {
        Objects.requireNonNull(definition, "definition");
    }
}

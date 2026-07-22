package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Objects;

/** Common identity contract implemented by every static or resolved AI Agent. */
public interface Agent {

    AgentDefinition definition();

    default AgentId id() {
        return definition().id();
    }

    public record AgentId(String value) {
        public AgentId {
            value = Objects.requireNonNull(value, "agent id").strip().toLowerCase();
            if (!value.matches("[a-z0-9][a-z0-9-]{1,79}")) {
                throw new IllegalArgumentException("Invalid agent id: " + value);
            }
        }
    }

    public record Instruction(String value) {
        public Instruction {
            value = Objects.requireNonNull(value, "agent instruction").strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("An Agent instruction is required.");
            }
        }
    }
}

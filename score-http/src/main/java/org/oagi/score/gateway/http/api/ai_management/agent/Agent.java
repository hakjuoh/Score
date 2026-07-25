package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Objects;

/** Definition-only identity contract implemented by each addressable AI Agent. */
public interface Agent {

    AgentDefinition definition();

    /** Request preparation owned by this Agent definition. */
    default AgentRequestHandler requestHandler() {
        return definition().requestHandler();
    }

    /** Tool binding policy owned by this Agent definition. */
    default AgentToolHandler toolHandler() {
        return definition().toolHandler();
    }

    /** Response interpretation owned by this Agent definition. */
    default AgentResponseHandler responseHandler() {
        return definition().responseHandler();
    }

    /** Input/output policy owned by this Agent definition. */
    default AgentGuardrails guardrails() {
        return definition().guardrails();
    }

    default AgentId id() {
        return definition().id();
    }

    /** Stable Workflow address; normally the same as the definition id. */
    default AgentId callId() {
        return id();
    }

    /** Whether a Planner may assign a task to this Agent. */
    default boolean assignable() {
        return definition().assignable();
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

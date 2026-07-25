package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Map;
import java.util.Objects;

/**
 * Requests a no-tool response regeneration after an output policy rejects a
 * candidate whose original Agent turn cannot be replayed safely.
 */
public final class AgentOutputRetryHandoffException extends RuntimeException {

    private final Agent.AgentId agentId;
    private final String candidate;
    private final String feedback;
    private final Map<String, Object> metadata;

    public AgentOutputRetryHandoffException(Agent.AgentId agentId, String candidate,
                                            String feedback, Map<String, Object> metadata) {
        super("Agent output requires response-only regeneration.");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.candidate = Objects.requireNonNullElse(candidate, "");
        this.feedback = Objects.requireNonNullElse(
                feedback, "Regenerate a response that satisfies the output policy.");
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public Agent.AgentId agentId() {
        return agentId;
    }

    public String candidate() {
        return candidate;
    }

    public String feedback() {
        return feedback;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}

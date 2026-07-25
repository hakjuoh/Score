package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral result returned by a complete Chat turn. */
public record AgentChatResult(String answer, Map<String, Object> traceMetadata,
                              Optional<AgentRunResult.Usage> usage) {

    public AgentChatResult(String answer) {
        this(answer, Map.of(), Optional.empty());
    }

    public AgentChatResult(String answer, Map<String, Object> traceMetadata) {
        this(answer, traceMetadata, Optional.empty());
    }

    public AgentChatResult {
        answer = Objects.requireNonNull(answer, "answer");
        traceMetadata = traceMetadata != null ? Map.copyOf(traceMetadata) : Map.of();
        usage = usage != null ? usage : Optional.empty();
    }

    public AgentChatResult withExecutionIdentity(String agentId, String modelId, String purpose) {
        Map<String, Object> metadata = new LinkedHashMap<>(traceMetadata);
        metadata.put("agentId", agentId);
        metadata.put("modelId", modelId);
        metadata.put("executionPurpose", purpose);
        return new AgentChatResult(answer, metadata, usage);
    }
}

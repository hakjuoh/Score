package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Observer-neutral result from one Agent run. */
public record AgentRunResult(AiMessage.Assistant response, List<AiMessage> generatedMessages,
                             Optional<Usage> usage, RunMetadata metadata) {

    public AgentRunResult {
        Objects.requireNonNull(response, "response");
        generatedMessages = generatedMessages != null ? List.copyOf(generatedMessages) : List.of(response);
        usage = usage != null ? usage : Optional.empty();
        metadata = metadata != null ? metadata : RunMetadata.empty();
    }

    public record Usage(long inputTokens, long outputTokens) {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("usage is negative");
        }
    }

    public record RunMetadata(Agent.AgentId agentId, AiModel.ModelId modelId,
                              String workflowId, Map<String, Object> attributes) {
        public RunMetadata {
            attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        }
        public static RunMetadata empty() { return new RunMetadata(null, null, null, Map.of()); }
    }
}

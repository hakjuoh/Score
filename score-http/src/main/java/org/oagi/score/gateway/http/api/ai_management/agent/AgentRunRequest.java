package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Request prepared by an Agent definition for the shared {@code AgentRunner}. */
public sealed interface AgentRunRequest permits AgentRunRequest.Model,
        AgentRunRequest.Chat, AgentRunRequest.Skip {

    /** A provider-neutral model invocation handled by {@code AgentExecutionService}. */
    record Model(String modelName, Agent.Instruction instruction, AiMessage.User input,
                 List<AiMessage> history, ExecutionScope scope,
                 Map<String, Object> observationContext)
            implements AgentRunRequest {
        public Model {
            modelName = required(modelName, "modelName");
            Objects.requireNonNull(instruction, "instruction");
            Objects.requireNonNull(input, "input");
            history = history != null ? List.copyOf(history) : List.of();
            Objects.requireNonNull(scope, "scope");
            observationContext = observationContext != null
                    ? Map.copyOf(observationContext) : Map.of();
        }
    }

    /** A full Chat execution handled by the shared Agent chat port. */
    record Chat(AgentExecutionContext context, Agent.Instruction instruction)
            implements AgentRunRequest {
        public Chat(AgentExecutionContext context) {
            this(context, null);
        }

        public Chat {
            Objects.requireNonNull(context, "context");
        }
    }

    /** A definition may finish or hand off without making a model call. */
    record Skip(AgentDecision decision) implements AgentRunRequest {
        public Skip {
            Objects.requireNonNull(decision, "decision");
        }
    }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        return normalized;
    }
}

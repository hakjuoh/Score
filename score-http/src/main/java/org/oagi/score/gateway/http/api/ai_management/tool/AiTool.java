package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.util.Map;
import java.util.Objects;

/** Protocol-neutral callable Tool. JSON is retained as the canonical wire contract. */
public interface AiTool {

    ToolSpecification specification();

    ToolResult execute(ToolArguments arguments, ToolExecutionContext context);

    record ToolId(String value) {
        public ToolId {
            value = Objects.requireNonNull(value, "tool id").strip();
            if (!value.matches("[A-Za-z0-9_.:-]{1,240}")) {
                throw new IllegalArgumentException("Invalid tool id: " + value);
            }
        }
    }

    record ToolSpecification(ToolId id, String name, String description,
                             String inputSchema, String outputSchema, ToolEffect effect) {
        public ToolSpecification {
            Objects.requireNonNull(id, "id");
            name = required(name, "name");
            description = Objects.requireNonNullElse(description, "");
            inputSchema = Objects.requireNonNullElse(inputSchema, "{}");
            outputSchema = Objects.requireNonNullElse(outputSchema, "{}");
            effect = effect != null ? effect : ToolEffect.UNKNOWN;
        }
    }

    record ToolArguments(String json) {
        public ToolArguments { json = Objects.requireNonNullElse(json, "{}"); }
    }

    record ToolResult(String json, Map<String, Object> metadata) {
        public ToolResult {
            json = Objects.requireNonNullElse(json, "");
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
        public ToolResult(String json) { this(json, Map.of()); }
    }

    record ToolExecutionContext(ExecutionScope scope, Map<String, Object> attributes) {
        public ToolExecutionContext {
            Objects.requireNonNull(scope, "scope");
            attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        }
    }

    enum ToolEffect { READ_ONLY, OUTPUT_WRITE, MUTATION, UNKNOWN }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Tool " + label + " is required.");
        return normalized;
    }
}

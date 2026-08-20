package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;

/**
 * Content-free lifecycle fact shared by execution observers. ATIF remains the
 * durable, content-bearing record; observers receive only correlation and timing metadata.
 */
public record AiExecutionLifecycle(String eventType, String subtype,
                                   String toolCallId, String toolName,
                                   Long toolCallSequence, Map<String, Object> metadata) {

    public static final String OBSERVATION_TYPE = "ai.lifecycle";
    private static final String ATTRIBUTE = "lifecycle";
    private static final Set<String> OBSERVABLE_METADATA = Set.of(
            "attempt", "max_attempts", "delay_millis", "status_code", "failure_class",
            "toolName", "mcp", "mcp_server_name", "mcp_protocol_version",
            "server_address", "server_port", "network_protocol_name", "network_transport",
            "duration_ms", "result_truncated",
            "truncationCause",
            "failure_type",
            "originalUtf8Bytes", "returnedUtf8Bytes", "toolOutputTokenLimit",
            "effectiveToolOutputTokenLimit",
            "contextUsage", "reason", "automatic", "batchId", "approved", "denied",
            "elicitationId",
            "workflow", "workflow_type", "node_id", "parent_node_id", "fanout_id", "depth", "member_count",
            "agent_run_id",
            "agent_count", "max_agents", "worker_count", "completed",
            "workflow_iteration", "iteration", "failed", "failed_count", "failure_count",
            "failed_agents");
    private static final Set<String> CANONICAL_METADATA = Set.of(
            ExecutionEventPublisher.EVENT_ID,
            ExecutionEventPublisher.EVENT_SEQUENCE,
            ExecutionEventPublisher.EVENT_OCCURRED_AT);
    private static final Set<String> BOOLEAN_METADATA = Set.of(
            "mcp", "result_truncated", "automatic");
    private static final Set<String> NUMERIC_METADATA = Set.of(
            "attempt", "max_attempts", "delay_millis", "status_code", "duration_ms", "server_port",
            "originalUtf8Bytes", "returnedUtf8Bytes", "toolOutputTokenLimit",
            "effectiveToolOutputTokenLimit",
            "approved", "denied", "depth", "member_count", "agent_count", "max_agents", "worker_count",
            "completed",
            "workflow_iteration", "iteration", "failed", "failed_count", "failure_count",
            "failed_agents");
    private static final Set<String> TYPE_METADATA = Set.of("failure_class", "failure_type");
    private static final Set<String> ID_METADATA = Set.of(
            "batchId", "elicitationId", "node_id", "parent_node_id", "fanout_id",
            "agent_run_id");

    public AiExecutionLifecycle {
        eventType = requiredToken(eventType, "eventType", 80);
        subtype = requiredToken(subtype, "subtype", 120);
        toolCallId = safeIdentifier(toolCallId, 256);
        toolName = safeIdentifier(toolName, 160);
        toolCallSequence = toolCallSequence != null && toolCallSequence >= 0
                ? toolCallSequence : null;
        metadata = observableMetadata(subtype, metadata);
    }

    public static AiExecutionLifecycle from(AiExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        return new AiExecutionLifecycle(event.type(), event.subtype(), event.toolCallId(),
                event.toolName(), event.toolCallSequence(), event.metadata());
    }

    public ExecutionObservation observation(ExecutionScope scope) {
        return ExecutionObservation.of(OBSERVATION_TYPE, scope, Map.of(ATTRIBUTE, this));
    }

    public ExecutionObservation observation(ExecutionScope scope, Instant occurredAt) {
        return new ExecutionObservation(OBSERVATION_TYPE, scope, occurredAt,
                Map.of(ATTRIBUTE, this));
    }

    public static Optional<AiExecutionLifecycle> from(ExecutionObservation observation) {
        if (observation == null || !OBSERVATION_TYPE.equals(observation.type())) {
            return Optional.empty();
        }
        Object lifecycle = observation.attributes().get(ATTRIBUTE);
        return lifecycle instanceof AiExecutionLifecycle value
                ? Optional.of(value) : Optional.empty();
    }

    private static String requiredToken(String value, String label, int maxLength) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (!normalized.matches("[A-Za-z0-9_.-]{1," + maxLength + "}")) {
            throw new IllegalArgumentException(label + " must be a bounded identifier");
        }
        return normalized;
    }

    private static Map<String, Object> observableMetadata(String subtype,
                                                           Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        java.util.stream.Stream.concat(OBSERVABLE_METADATA.stream(), CANONICAL_METADATA.stream())
                .forEach(name -> {
            Object sanitized = sanitizedMetadataValue(subtype, name, source.get(name));
            if (sanitized != null) safe.put(name, sanitized);
        });
        return safe.isEmpty() ? Map.of() : Map.copyOf(safe);
    }

    private static Object sanitizedMetadataValue(String subtype, String name, Object value) {
        if (value == null) return null;
        if (ExecutionEventPublisher.EVENT_ID.equals(name)) return safeIdentifier(value.toString(), 64);
        if (ExecutionEventPublisher.EVENT_SEQUENCE.equals(name)) {
            return value instanceof Number number ? Math.max(1L, number.longValue()) : null;
        }
        if (ExecutionEventPublisher.EVENT_OCCURRED_AT.equals(name)) {
            try {
                return java.time.Instant.parse(value.toString()).toString();
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
        if (BOOLEAN_METADATA.contains(name)) return value instanceof Boolean ? value : null;
        if (NUMERIC_METADATA.contains(name)) {
            return value instanceof Number number ? number.longValue() : null;
        }
        if (TYPE_METADATA.contains(name)) return safeType(value);
        if (ID_METADATA.contains(name)) return safeIdentifier(value.toString(), 256);
        return switch (name) {
            case "toolName" -> safeIdentifier(value.toString(), 160);
            case "truncationCause" -> truncationCause(value);
            case "mcp_server_name", "server_address" -> safeIdentifier(value.toString(), 160);
            case "mcp_protocol_version", "network_protocol_name", "network_transport" ->
                    safeIdentifier(value.toString(), 80);
            case "workflow" -> safeIdentifier(value.toString(), 100);
            case "workflow_type" -> workflowType(value);
            case "reason" -> "context_compacted".equals(subtype)
                    ? compactionReason(value.toString()) : null;
            case "contextUsage" -> value instanceof AiContextUsageInfo usage
                    ? sanitizedContextUsage(usage) : null;
            default -> null;
        };
    }

    private static AiContextUsageInfo sanitizedContextUsage(AiContextUsageInfo usage) {
        return new AiContextUsageInfo(
                Objects.requireNonNullElse(safeIdentifier(usage.modelName(), 160), "unknown"),
                Math.max(0L, usage.currentInputTokens()),
                Math.max(0L, usage.contextWindow()),
                Math.max(0L, usage.safeInputLimit()),
                Math.max(0L, usage.remainingTokens()),
                Math.max(0.0, Math.min(100.0, usage.usedPercent())),
                usage.estimated(),
                Objects.requireNonNullElse(safeIdentifier(usage.source(), 80), "unknown"));
    }

    private static String compactionReason(String value) {
        String normalized = value != null ? value.strip().toLowerCase() : "";
        return switch (normalized) {
            case "manual", "threshold" -> normalized;
            default -> "other";
        };
    }

    private static String workflowType(Object value) {
        String normalized = value.toString().strip().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "direct", "sequential", "parallel" -> normalized;
            default -> "unknown";
        };
    }

    private static String truncationCause(Object value) {
        String normalized = value.toString().strip().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "tool_output_limit", "remaining_context" -> normalized;
            default -> "unknown";
        };
    }

    private static String safeType(Object value) {
        String normalized = value.toString().strip();
        return normalized.matches("[A-Za-z0-9_.$-]{1,160}") ? normalized : "unknown";
    }

    private static String safeIdentifier(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0," + (maxLength - 1) + "}")
                ? normalized : "unknown";
    }
}

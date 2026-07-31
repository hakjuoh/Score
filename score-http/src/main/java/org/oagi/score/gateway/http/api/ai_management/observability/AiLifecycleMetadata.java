package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventPublisher;

import java.util.Map;
import java.util.Objects;

/** Normalizes bounded lifecycle metadata into trace and metric dimensions. */
final class AiLifecycleMetadata {
    private AiLifecycleMetadata() { }

    static void setStringAttribute(SpanBuilder builder, String key, Object value) {
        String candidate = Objects.toString(value, null);
        if (candidate != null && !candidate.isBlank() && !"unknown".equals(candidate)) {
            builder.setAttribute(key, candidate);
        }
    }

    static void setEventIdentity(SpanBuilder builder, Map<String, Object> metadata) {
        setStringAttribute(builder, ExecutionEventPublisher.EVENT_ID,
                metadata.get(ExecutionEventPublisher.EVENT_ID));
        Object sequence = metadata.get(ExecutionEventPublisher.EVENT_SEQUENCE);
        if (sequence instanceof Number number && number.longValue() > 0) {
            builder.setAttribute(ExecutionEventPublisher.EVENT_SEQUENCE, number.longValue());
        }
        setStringAttribute(builder, ExecutionEventPublisher.EVENT_OCCURRED_AT,
                metadata.get(ExecutionEventPublisher.EVENT_OCCURRED_AT));
        Object occurredAt = metadata.get(ExecutionEventPublisher.EVENT_OCCURRED_AT);
        if (occurredAt != null) {
            try {
                builder.setStartTimestamp(java.time.Instant.parse(occurredAt.toString()));
            } catch (java.time.format.DateTimeParseException ignored) { }
        }
    }

    static void setTerminalEventIdentity(Span span, Map<String, Object> metadata) {
        Object id = metadata.get(ExecutionEventPublisher.EVENT_ID);
        if (id != null) span.setAttribute("score.event.end.id", id.toString());
        Object sequence = metadata.get(ExecutionEventPublisher.EVENT_SEQUENCE);
        if (sequence instanceof Number number) {
            span.setAttribute("score.event.end.sequence", number.longValue());
        }
        Object occurredAt = metadata.get(ExecutionEventPublisher.EVENT_OCCURRED_AT);
        if (occurredAt != null) {
            span.setAttribute("score.event.end.occurred_at", occurredAt.toString());
        }
    }

    static boolean implicitRootQueue(Map<String, Object> metadata) {
        return metadata.get("parent_node_id") == null && number(metadata.get("depth")) == 0;
    }

    static boolean workflowEvent(String subtype) {
        return !"unknown".equals(terminalSuffix(subtype))
                && "workflow".equals(workflowPrefix(subtype));
    }

    static String terminalSuffix(String subtype) {
        for (String suffix : new String[]{"output_retry_handoff", "started", "planned",
                "synthesizing", "completed", "failed", "cancelled", "refused", "stalled"}) {
            if (subtype.endsWith("_" + suffix)) return suffix;
        }
        return "unknown";
    }

    static String workflowPrefix(String subtype) {
        String suffix = terminalSuffix(subtype);
        return subtype.endsWith("_" + suffix)
                ? subtype.substring(0, subtype.length() - suffix.length() - 1) : subtype;
    }

    static String workflowMetricName(String workflow) {
        return "main".equals(AiObservationInstruments.normalized(workflow))
                ? "main" : "recursive";
    }

    static String workflowType(Map<String, Object> metadata) {
        String normalized = Objects.toString(metadata.get("workflow_type"), "unknown")
                .strip().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "direct", "sequential", "parallel" -> normalized;
            default -> "unknown";
        };
    }

    static String outcome(String status) {
        String normalized = status != null ? status.strip().toLowerCase() : "unknown";
        return switch (normalized) {
            case "completed", "complete", "success" -> "success";
            case "timed_out", "timeout" -> "timeout";
            case "stalled" -> "stalled";
            case "cancelled", "canceled", "output_retry_handoff" -> "cancelled";
            case "denied", "blocked", "refused" -> "refused";
            case "failed", "error", "partial_failure" -> normalized;
            case "admission_rejected" -> "admission_rejected";
            case "unknown_reconciliation_required" -> "error";
            default -> "unknown";
        };
    }

    static boolean hasFailures(Map<String, Object> metadata) {
        return firstPositive(metadata, "failed", "failed_count", "failure_count",
                "failed_agents") > 0;
    }

    static long firstPositive(Map<String, Object> metadata, String... names) {
        for (String name : names) {
            long value = number(metadata.get(name));
            if (value > 0) return value;
        }
        return 0;
    }

    static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value != null ? Long.parseLong(value.toString()) : -1L; }
        catch (NumberFormatException ignored) { return -1L; }
    }

    static String boundedType(Object value) {
        String type = Objects.toString(value, "unknown").strip();
        return type.matches("[A-Za-z0-9_.$-]{1,120}") ? type : "unknown";
    }

    static String statusClass(long status) {
        return status >= 100 && status <= 599 ? (status / 100) + "xx" : "unknown";
    }

}

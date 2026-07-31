package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.common.Attributes;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/** Builds bounded, low-cardinality observation labels. */
final class AiObservationLabels {
    private AiObservationLabels() { }

    static Attributes model(String model, String outcome) {
        var attributes = Attributes.builder().put("gen_ai.request.model", value(model));
        if (outcome != null) attributes.put("score.ai.outcome", outcome);
        return attributes.build();
    }

    static Attributes providerModel(String provider, String model, String outcome) {
        var attributes = Attributes.builder()
                .put("gen_ai.provider.name", value(provider))
                .put("gen_ai.request.model", value(model));
        if (outcome != null) attributes.put("score.ai.outcome", outcome);
        return attributes.build();
    }

    static String value(String value) {
        return StringUtils.hasText(value) ? value.strip() : "unknown";
    }

    static String finishReasonCategory(String reason) {
        return switch (AiObservationInstruments.normalized(reason)) {
            case "stop", "end_turn", "stop_sequence" -> "stop";
            case "length", "max_tokens" -> "length";
            case "tool_calls", "tool_use" -> "tool_calls";
            case "content_filter", "refusal" -> "content_filter";
            case "error" -> "error";
            default -> "unknown";
        };
    }

    static String finishReasonValue(String reason) {
        String normalized = reason != null ? reason.strip() : "";
        return normalized.matches("[A-Za-z0-9_.:/-]{1,80}") ? normalized : "_OTHER";
    }

    static String admissionReasonCategory(String reason) {
        String normalized = AiObservationInstruments.normalized(reason);
        return switch (normalized) {
            case "registry_capacity", "user_limit", "conversation_busy", "duplicate_request",
                 "validation", "preparation_failed", "executor_rejected",
                 "transport_send_failed", "admission_failed" -> normalized;
            default -> "other";
        };
    }

    static BigDecimal nonNegativeDecimal(Object value) {
        if (value == null) return null;
        try {
            String text = value.toString().strip();
            if (text.length() > 40 || !text.matches("[0-9]+(?:\\.[0-9]+)?")) return null;
            BigDecimal amount = new BigDecimal(text);
            return amount.signum() >= 0 ? amount : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

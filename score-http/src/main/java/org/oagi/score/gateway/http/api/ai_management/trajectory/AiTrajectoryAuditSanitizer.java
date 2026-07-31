package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AiSensitiveDataRedactor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Redacts and bounds tool arguments and results before durable audit storage. */
final class AiTrajectoryAuditSanitizer {

    private static final int MAX_AUDIT_TEXT_CHARS = 32_768;
    private final ObjectMapper objectMapper;

    AiTrajectoryAuditSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    String auditText(String value) {
        String source = Objects.requireNonNullElse(value, "");
        int omitted = Math.max(0, source.length() - MAX_AUDIT_TEXT_CHARS);
        String candidate = omitted > 0 ? source.substring(0, MAX_AUDIT_TEXT_CHARS) : source;
        String sanitized = AiSensitiveDataRedactor.redactText(candidate);
        try {
            Object parsed = objectMapper.readValue(candidate, Object.class);
            if (parsed instanceof Map<?, ?> || parsed instanceof List<?>) {
                sanitized = objectMapper.writeValueAsString(redact(parsed));
            }
        } catch (JsonProcessingException ignored) {
            // Non-JSON tool output is redacted with the conservative text pattern.
        }
        if (sanitized.length() > MAX_AUDIT_TEXT_CHARS) {
            omitted += sanitized.length() - MAX_AUDIT_TEXT_CHARS;
            sanitized = sanitized.substring(0, MAX_AUDIT_TEXT_CHARS);
        }
        return omitted > 0 ? sanitized + "\n[TRUNCATED " + omitted + " CHARACTERS]" : sanitized;
    }

    Object boundedValue(Object value) {
        Object redacted = redact(value);
        String serialized = json(redacted);
        if (serialized.length() <= MAX_AUDIT_TEXT_CHARS) return redacted;
        return Map.of("truncated", true, "summary", boundedText(serialized));
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return Objects.toString(value);
        }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = Objects.toString(key);
                result.put(name, AiSensitiveDataRedactor.isSensitiveKey(name)
                        ? "[REDACTED]" : redact(item));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value instanceof String text ? auditText(text) : value;
    }

    private String boundedText(String value) {
        if (value.length() <= MAX_AUDIT_TEXT_CHARS) return value;
        return value.substring(0, MAX_AUDIT_TEXT_CHARS)
                + "\n[TRUNCATED " + (value.length() - MAX_AUDIT_TEXT_CHARS) + " CHARACTERS]";
    }
}

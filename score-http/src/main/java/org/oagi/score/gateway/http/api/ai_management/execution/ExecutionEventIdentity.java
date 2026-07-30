package org.oagi.score.gateway.http.api.ai_management.execution;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.util.StringUtils;

/** Canonical identity assigned to one ordered execution observation. */
public record ExecutionEventIdentity(String eventId, long sequence, Instant occurredAt) {

    public ExecutionEventIdentity {
        if (!StringUtils.hasText(eventId)) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public void putAttributes(Map<String, Object> target) {
        Objects.requireNonNull(target, "target");
        target.put(ExecutionEventPublisher.EVENT_ID, eventId);
        target.put(ExecutionEventPublisher.EVENT_SEQUENCE, sequence);
        target.put(ExecutionEventPublisher.EVENT_OCCURRED_AT, occurredAt.toString());
    }

    public static Optional<ExecutionEventIdentity> find(ExecutionObservation observation) {
        if (observation == null) return Optional.empty();
        Object id = observation.attributes().get(ExecutionEventPublisher.EVENT_ID);
        Object sequence = observation.attributes().get(ExecutionEventPublisher.EVENT_SEQUENCE);
        if (id == null || !StringUtils.hasText(id.toString())
                || !(sequence instanceof Number number) || number.longValue() < 1L
                || observation.occurredAt() == null) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionEventIdentity(
                id.toString(), number.longValue(), observation.occurredAt()));
    }

    /** Copies canonical wire attributes without inventing an identity for a non-canonical event. */
    public static void copyAttributes(ExecutionObservation observation,
                                      Map<String, Object> target) {
        copyAttributes(observation != null ? observation.attributes() : null, target);
    }

    public static void copyAttributes(Map<String, Object> attributes,
                                      Map<String, Object> target) {
        if (attributes == null || target == null) return;
        copy(attributes, target, ExecutionEventPublisher.EVENT_ID);
        copy(attributes, target, ExecutionEventPublisher.EVENT_SEQUENCE);
        copy(attributes, target, ExecutionEventPublisher.EVENT_OCCURRED_AT);
    }

    private static void copy(Map<String, Object> attributes,
                             Map<String, Object> target, String name) {
        Object value = attributes.get(name);
        if (value != null) target.put(name, value);
    }
}

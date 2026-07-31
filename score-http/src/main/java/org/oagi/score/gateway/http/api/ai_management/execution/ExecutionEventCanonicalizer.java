package org.oagi.score.gateway.http.api.ai_management.execution;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Assigns immutable identity and monotonic occurrence time to an execution event. */
final class ExecutionEventCanonicalizer {
    private ExecutionEventCanonicalizer() { }

    static CanonicalEvent canonicalize(ExecutionObservation source, long sequence,
                                       Instant previousOccurredAt) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant occurredAt = previousOccurredAt != null && !now.isAfter(previousOccurredAt)
                ? previousOccurredAt.plus(1, ChronoUnit.MICROS) : now;
        Map<String, Object> attributes = new LinkedHashMap<>(source.attributes());
        String eventId = UUID.randomUUID().toString();
        attributes.put(ExecutionEventPublisher.EVENT_ID, eventId);
        attributes.put(ExecutionEventPublisher.EVENT_SEQUENCE, sequence);
        attributes.put(ExecutionEventPublisher.EVENT_OCCURRED_AT, occurredAt.toString());
        AiExecutionLifecycle.from(source).ifPresent(lifecycle -> {
            Map<String, Object> metadata = new LinkedHashMap<>(lifecycle.metadata());
            metadata.put(ExecutionEventPublisher.EVENT_ID, eventId);
            metadata.put(ExecutionEventPublisher.EVENT_SEQUENCE, sequence);
            metadata.put(ExecutionEventPublisher.EVENT_OCCURRED_AT, occurredAt.toString());
            attributes.put("lifecycle", new AiExecutionLifecycle(
                    lifecycle.eventType(), lifecycle.subtype(), lifecycle.toolCallId(),
                    lifecycle.toolName(), lifecycle.toolCallSequence(), metadata));
        });
        return new CanonicalEvent(
                new ExecutionObservation(source.type(), source.scope(), occurredAt, attributes),
                occurredAt);
    }

    record CanonicalEvent(ExecutionObservation observation, Instant occurredAt) { }
}

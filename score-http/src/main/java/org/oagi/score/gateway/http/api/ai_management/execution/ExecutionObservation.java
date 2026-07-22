package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Immutable, content-minimized lifecycle fact. Observations never control execution. */
public record ExecutionObservation(String type, ExecutionScope scope, Instant occurredAt,
                                   Map<String, Object> attributes) {
    public ExecutionObservation {
        type = Objects.requireNonNull(type, "type").strip();
        if (type.isEmpty()) throw new IllegalArgumentException("observation type is required");
        Objects.requireNonNull(scope, "scope");
        occurredAt = occurredAt != null ? occurredAt : Instant.now();
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    public static ExecutionObservation of(String type, ExecutionScope scope,
                                          Map<String, Object> attributes) {
        return new ExecutionObservation(type, scope, Instant.now(), attributes);
    }
}

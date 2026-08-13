package org.oagi.score.gateway.http.api.activity_management.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Language-neutral wire model for user activity produced by SCORE applications.
 *
 * <p>The activity name, targets, and properties describe the user-visible action. The source only
 * identifies which entry point performed it, so Java and Python producers can describe the same
 * action in the same form.</p>
 */
public record ScoreActivityEvent(
        String schemaVersion,
        String eventId,
        Instant occurredAt,
        String name,
        String source,
        String outcome,
        ScoreActivityActor actor,
        List<ScoreActivityTarget> targets,
        Map<String, Object> properties,
        ScoreActivityContext context) {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    public ScoreActivityEvent {
        requireNonNull(schemaVersion, "schemaVersion must not be null");
        requireNonNull(eventId, "eventId must not be null");
        requireNonNull(occurredAt, "occurredAt must not be null");
        requireNonNull(name, "name must not be null");
        requireNonNull(source, "source must not be null");
        requireNonNull(outcome, "outcome must not be null");
        requireNonNull(actor, "actor must not be null");
        targets = List.copyOf(requireNonNull(targets, "targets must not be null"));
        properties = immutableJsonObject(requireNonNull(properties, "properties must not be null"));
        context = context == null ? ScoreActivityContext.empty() : context;
    }

    private static Map<String, Object> immutableJsonObject(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                requireNonNull(key, "property key must not be null"),
                immutableJsonValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("Activity property map keys must be strings");
                }
                copy.put(stringKey, immutableJsonValue(child));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            iterable.forEach(child -> copy.add(immutableJsonValue(child)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(
                "Activity properties must contain only JSON-compatible values: " + value.getClass().getName());
    }

    public ScoreActivityEvent withContext(ScoreActivityContext replacement) {
        return new ScoreActivityEvent(
                schemaVersion, eventId, occurredAt, name, source, outcome,
                actor, targets, properties, replacement);
    }
}

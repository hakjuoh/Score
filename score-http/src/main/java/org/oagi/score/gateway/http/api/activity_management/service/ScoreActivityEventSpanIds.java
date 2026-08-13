package org.oagi.score.gateway.http.api.activity_management.service;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.sdk.trace.IdGenerator;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ensures that a batch never maps multiple activity events to the same OTLP span identity. */
final class ScoreActivityEventSpanIds {

    private static final IdGenerator IDS = IdGenerator.random();

    private ScoreActivityEventSpanIds() {
    }

    static List<ScoreActivityEvent> unique(List<ScoreActivityEvent> events) {
        if (events == null || events.size() < 2) {
            return events;
        }
        Set<String> identities = new HashSet<>();
        List<ScoreActivityEvent> normalized = new ArrayList<>(events.size());
        for (ScoreActivityEvent event : events) {
            ScoreActivityContext context = event.context();
            String identity = identity(context);
            if (identity == null || identities.add(identity)) {
                normalized.add(event);
                continue;
            }
            String spanId;
            do {
                spanId = IDS.generateSpanId();
            } while (!identities.add(context.traceId() + ':' + spanId));
            normalized.add(event.withContext(context.withSpanId(spanId)));
        }
        return List.copyOf(normalized);
    }

    private static String identity(ScoreActivityContext context) {
        if (context == null
                || !TraceId.isValid(context.traceId())
                || !SpanId.isValid(context.spanId())) {
            return null;
        }
        return context.traceId() + ':' + context.spanId();
    }
}

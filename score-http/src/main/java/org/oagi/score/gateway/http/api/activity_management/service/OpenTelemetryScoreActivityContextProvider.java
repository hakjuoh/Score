package org.oagi.score.gateway.http.api.activity_management.service;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.trace.ScoreActivityTracing;

import java.util.LinkedHashMap;

import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_ID_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TIMESTAMP_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TYPE_BAGGAGE;

/** Reads trace correlation from the current OpenTelemetry context when one exists. */
public final class OpenTelemetryScoreActivityContextProvider implements ScoreActivityContextProvider {

    @Override
    public ScoreActivityContext currentContext() {
        SpanContext spanContext = Span.current().getSpanContext();
        Baggage baggage = Baggage.current();
        if (!spanContext.isValid()) {
            return new ScoreActivityContext(
                    null, null, null, null, null, null,
                    baggage.getEntryValue(REQUEST_TYPE_BAGGAGE),
                    baggage.getEntryValue(REQUEST_ID_BAGGAGE),
                    baggage.getEntryValue(REQUEST_TIMESTAMP_BAGGAGE),
                    ScoreActivityTracing.currentHttpRequest());
        }
        var activityStartedAt = ScoreActivityTracing.currentActivityStartedAt();
        return new ScoreActivityContext(
                spanContext.getTraceId(), spanContext.getSpanId(),
                ScoreActivityTracing.currentActivityParentSpanId(),
                spanContext.getTraceFlags().asHex(),
                traceState(spanContext),
                activityStartedAt == null ? null : activityStartedAt.toString(),
                baggage.getEntryValue(REQUEST_TYPE_BAGGAGE),
                baggage.getEntryValue(REQUEST_ID_BAGGAGE),
                baggage.getEntryValue(REQUEST_TIMESTAMP_BAGGAGE),
                ScoreActivityTracing.currentHttpRequest());
    }

    private static String traceState(SpanContext spanContext) {
        var carrier = new LinkedHashMap<String, String>();
        W3CTraceContextPropagator.getInstance().inject(
                Context.root().with(Span.wrap(spanContext)), carrier, java.util.Map::put);
        return carrier.get("tracestate");
    }
}

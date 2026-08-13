package org.oagi.score.gateway.http.api.activity_management.trace;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_ID_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TIMESTAMP_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TYPE_BAGGAGE;

/** Propagates standard W3C trace context and baggage through SCORE application events. */
public final class ScoreTraceContextPropagator {

    private static final TextMapPropagator W3C_PROPAGATOR = TextMapPropagator.composite(
            W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    public Map<String, String> capture() {
        Map<String, String> carrier = new LinkedHashMap<>();
        Context context = Context.current().with(scoreRequestBaggage());
        W3C_PROPAGATOR.inject(context, carrier, Map::put);
        return Map.copyOf(carrier);
    }

    private static Baggage scoreRequestBaggage() {
        Baggage current = Baggage.current();
        BaggageBuilder safe = Baggage.builder();
        copyIfPresent(current, safe, REQUEST_TYPE_BAGGAGE);
        copyIfPresent(current, safe, REQUEST_ID_BAGGAGE);
        copyIfPresent(current, safe, REQUEST_TIMESTAMP_BAGGAGE);
        return safe.build();
    }

    private static void copyIfPresent(Baggage source, BaggageBuilder target, String key) {
        String value = source.getEntryValue(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    public ContinuedTrace continueConsumer(String eventName, Map<String, String> carrier) {
        Map<String, String> safeCarrier = carrier == null ? Map.of() : carrier;
        Context parent = W3C_PROPAGATOR.extract(Context.root(), safeCarrier, MAP_GETTER);
        var parentSpan = Span.fromContext(parent).getSpanContext();
        Context consumerContext = parentSpan.isValid()
                ? ScoreActivityTracing.requestContext(parent, parentSpan, parentSpan.getSpanId())
                : parent;
        return new ContinuedTrace(consumerContext.makeCurrent());
    }

    public static final class ContinuedTrace implements AutoCloseable {

        private final Scope scope;

        private ContinuedTrace(Scope scope) {
            this.scope = scope;
        }

        public void failed(Throwable failure) {
            // Business failures are represented by the emitted activity event.
        }

        @Override
        public void close() {
            scope.close();
        }
    }
}

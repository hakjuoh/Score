package org.oagi.score.gateway.http.api.activity_management.trace;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.service.OpenTelemetryScoreActivityContextProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreTraceContextPropagatorTest {

    private final ScoreTraceContextPropagator propagator = new ScoreTraceContextPropagator();
    private final ScoreActivityTracing tracing = new ScoreActivityTracing();
    private final OpenTelemetryScoreActivityContextProvider contextProvider =
            new OpenTelemetryScoreActivityContextProvider();

    @Test
    void continuesTheProducerActivityAsTheParentAcrossAnEventCarrier() {
        SpanContext requestSpan = SpanContext.create(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                TraceFlags.getSampled(),
                TraceState.builder().put("vendor", "state").build());
        Baggage baggage = Baggage.builder()
                .put(ScoreRequestHeaders.REQUEST_ID_BAGGAGE, "ui-request-1")
                .put("untrusted.private", "must-not-propagate")
                .build();
        Context requestContext = ScoreActivityTracing.requestContext(
                Context.root().with(baggage), requestSpan, null);

        Map<String, String> carrier;
        ScoreActivityContext producerContext;
        try (Scope ignored = requestContext.makeCurrent();
             var producer = tracing.start(invocation("release", "state-change"))) {
            producerContext = contextProvider.currentContext();
            carrier = propagator.capture();
        }

        ScoreActivityContext consumerActivityContext;
        String continuedRequestId;
        try (var consumer = propagator.continueConsumer("releaseCleanupEvent", carrier);
             var activity = tracing.start(invocation("acc", "state-change"))) {
            consumerActivityContext = contextProvider.currentContext();
            continuedRequestId = Baggage.current().getEntryValue(ScoreRequestHeaders.REQUEST_ID_BAGGAGE);
        }

        assertThat(carrier).containsKeys("traceparent", "tracestate", "baggage");
        assertThat(carrier.get("tracestate")).contains("vendor=state");
        assertThat(carrier.get("baggage")).doesNotContain("untrusted.private");
        assertThat(consumerActivityContext.traceId()).isEqualTo(producerContext.traceId());
        assertThat(consumerActivityContext.parentSpanId()).isEqualTo(producerContext.spanId());
        assertThat(continuedRequestId).isEqualTo("ui-request-1");
    }

    @Test
    void ignoresAnInvalidCarrierAndAllowsANewActivityTrace() {
        ScoreActivityContext context;
        try (var consumer = propagator.continueConsumer(
                "releaseCreateRequestEvent", Map.of("traceparent", "invalid"));
             var activity = tracing.start(invocation("release", "create"))) {
            context = contextProvider.currentContext();
            consumer.failed(new IllegalArgumentException("ignored by tracing"));
        }

        assertThat(context.traceId()).matches("[0-9a-f]{32}");
        assertThat(context.parentSpanId()).isNull();
    }

    private static ScoreActivityInvocation invocation(String category, String action) {
        return new ScoreActivityInvocation(category, action, List.of());
    }
}

package org.oagi.score.gateway.http.api.activity_management.trace;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
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

import static org.assertj.core.api.Assertions.assertThat;

class ScoreActivityTracingTest {

    private final ScoreActivityTracing tracing = new ScoreActivityTracing();
    private final OpenTelemetryScoreActivityContextProvider contextProvider =
            new OpenTelemetryScoreActivityContextProvider();

    @Test
    void createsLogicalReleaseAndComponentHierarchyWithoutRecordingSpans() {
        SpanContext upstream = SpanContext.createFromRemoteParent(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        SpanContext request = SpanContext.create(
                upstream.getTraceId(),
                "1111111111111111",
                TraceFlags.getSampled(),
                TraceState.builder().put("vendor", "state").build());
        Baggage baggage = Baggage.builder()
                .put(ScoreRequestHeaders.REQUEST_TYPE_BAGGAGE, "RELEASE_PUBLISHED")
                .put(ScoreRequestHeaders.REQUEST_ID_BAGGAGE, "ui-request-1")
                .put(ScoreRequestHeaders.REQUEST_TIMESTAMP_BAGGAGE, "2026-08-06T12:00:00Z")
                .build();
        Context requestContext = ScoreActivityTracing.requestContext(
                Context.root().with(baggage), request, upstream.getSpanId());

        ScoreActivityContext releaseContext;
        ScoreActivityContext componentContext;
        try (Scope ignored = requestContext.makeCurrent();
             var release = tracing.start(invocation("release", "state-change"))) {
            releaseContext = contextProvider.currentContext();
            try (var component = tracing.start(invocation("acc", "state-change"))) {
                componentContext = contextProvider.currentContext();
                component.succeeded();
            }
            release.succeeded();
        }

        assertThat(releaseContext.traceId()).isEqualTo(upstream.getTraceId());
        assertThat(releaseContext.parentSpanId()).isEqualTo(upstream.getSpanId());
        assertThat(releaseContext.activityStartedAt()).isNotNull();
        assertThat(releaseContext.traceFlags()).isEqualTo("01");
        assertThat(releaseContext.traceState()).isEqualTo("vendor=state");
        assertThat(releaseContext.requestId()).isEqualTo("ui-request-1");
        assertThat(componentContext.traceId()).isEqualTo(releaseContext.traceId());
        assertThat(componentContext.parentSpanId()).isEqualTo(releaseContext.spanId());
        assertThat(componentContext.spanId()).isNotEqualTo(releaseContext.spanId());
        assertThat(Span.wrap(SpanContext.create(
                componentContext.traceId(), componentContext.spanId(), TraceFlags.getSampled(),
                TraceState.getDefault())).isRecording()).isFalse();
    }

    @Test
    void createsAnUnparentedActivityWhenThereIsNoInboundTrace() {
        ScoreActivityContext context;
        try (var activity = tracing.start(invocation("acc", "update"))) {
            context = contextProvider.currentContext();
            activity.failed(new IllegalArgumentException("invalid"));
        }

        assertThat(context.traceId()).matches("[0-9a-f]{32}");
        assertThat(context.spanId()).matches("[0-9a-f]{16}");
        assertThat(context.parentSpanId()).isNull();
    }

    private static ScoreActivityInvocation invocation(String category, String action) {
        return new ScoreActivityInvocation(category, action, List.of());
    }
}

package org.oagi.score.gateway.http.api.activity_management.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.IdGenerator;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreHttpRequest;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Creates lightweight OpenTelemetry-compatible correlation scopes for {@code @ScoreActivity}.
 * It never records or exports spans; the asynchronous activity sink is the only OTLP writer.
 */
public final class ScoreActivityTracing {

    private static final ContextKey<String> CURRENT_ACTIVITY_SPAN_ID =
            ContextKey.named("score-current-activity-span-id");
    private static final ContextKey<String> CURRENT_ACTIVITY_PARENT_SPAN_ID =
            ContextKey.named("score-current-activity-parent-span-id");
    private static final ContextKey<String> REQUEST_UPSTREAM_SPAN_ID =
            ContextKey.named("score-request-upstream-span-id");
    private static final ContextKey<Instant> CURRENT_ACTIVITY_STARTED_AT =
            ContextKey.named("score-current-activity-started-at");
    private static final ContextKey<ScoreHttpRequest> CURRENT_HTTP_REQUEST =
            ContextKey.named("score-current-http-request");
    private static final IdGenerator ID_GENERATOR = IdGenerator.random();

    public ActivitySpan start(ScoreActivityInvocation invocation) {
        requireNonNull(invocation, "invocation must not be null");
        Context current = Context.current();
        SpanContext currentSpan = Span.fromContext(current).getSpanContext();
        String traceId = currentSpan.isValid()
                ? currentSpan.getTraceId()
                : ID_GENERATOR.generateTraceId();
        String spanId = ID_GENERATOR.generateSpanId();
        String parentSpanId = current.get(CURRENT_ACTIVITY_SPAN_ID);
        if (parentSpanId == null) {
            parentSpanId = current.get(REQUEST_UPSTREAM_SPAN_ID);
        }
        SpanContext activitySpan = SpanContext.create(
                traceId,
                spanId,
                currentSpan.isValid() ? currentSpan.getTraceFlags() : TraceFlags.getSampled(),
                currentSpan.isValid() ? currentSpan.getTraceState() : TraceState.getDefault());
        Context activityContext = current
                .with(Span.wrap(activitySpan))
                .with(CURRENT_ACTIVITY_SPAN_ID, spanId)
                .with(CURRENT_ACTIVITY_STARTED_AT, Instant.now());
        if (parentSpanId != null) {
            activityContext = activityContext.with(CURRENT_ACTIVITY_PARENT_SPAN_ID, parentSpanId);
        }
        return new ActivitySpan(activityContext.makeCurrent());
    }

    static Context requestContext(Context base, SpanContext requestSpan, String upstreamSpanId) {
        return requestContext(base, requestSpan, upstreamSpanId, null);
    }

    static Context requestContext(
            Context base,
            SpanContext requestSpan,
            String upstreamSpanId,
            ScoreHttpRequest httpRequest) {
        Context context = base.with(Span.wrap(requestSpan));
        if (upstreamSpanId != null) {
            context = context.with(REQUEST_UPSTREAM_SPAN_ID, upstreamSpanId);
        }
        return httpRequest == null ? context : context.with(CURRENT_HTTP_REQUEST, httpRequest);
    }

    public static String currentActivityParentSpanId() {
        return Context.current().get(CURRENT_ACTIVITY_PARENT_SPAN_ID);
    }

    public static Instant currentActivityStartedAt() {
        return Context.current().get(CURRENT_ACTIVITY_STARTED_AT);
    }

    public static ScoreHttpRequest currentHttpRequest() {
        return Context.current().get(CURRENT_HTTP_REQUEST);
    }

    public static final class ActivitySpan implements AutoCloseable {

        private final Scope scope;

        private ActivitySpan(Scope scope) {
            this.scope = scope;
        }

        public void succeeded() {
            // Outcome is represented by the event exported after transaction completion.
        }

        public void failed(Throwable failure) {
            // Outcome is represented by the event exported after transaction completion.
        }

        @Override
        public void close() {
            scope.close();
        }
    }
}

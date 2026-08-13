package org.oagi.score.gateway.http.api.activity_management.trace;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.trace.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_ID_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_ID_HEADER;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TIMESTAMP_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TIMESTAMP_HEADER;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TYPE_BAGGAGE;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.REQUEST_TYPE_HEADER;
import static org.oagi.score.gateway.http.api.activity_management.trace.ScoreRequestHeaders.TRACE_ID_HEADER;

/** Converts UI request metadata into OpenTelemetry baggage and span attributes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public final class ScoreRequestCorrelationFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final String DEFAULT_REQUEST_TYPE = "UI_HTTP_REQUEST";
    private static final IdGenerator ID_GENERATOR = IdGenerator.random();
    private static final TextMapPropagator W3C_PROPAGATOR = TextMapPropagator.composite(
            W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());
    private static final TextMapGetter<HttpServletRequest> REQUEST_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return carrier == null
                    ? Collections.emptyList()
                    : Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    };

    private final Clock clock;

    public ScoreRequestCorrelationFilter() {
        this(Clock.systemUTC());
    }

    ScoreRequestCorrelationFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Context extracted = W3C_PROPAGATOR.extract(Context.root(), request, REQUEST_GETTER);
        SpanContext inboundSpan = Span.fromContext(extracted).getSpanContext();
        Baggage inboundBaggage = Baggage.fromContext(extracted);
        String requestType = firstSafeValue(
                request.getHeader(REQUEST_TYPE_HEADER),
                inboundBaggage.getEntryValue(REQUEST_TYPE_BAGGAGE),
                DEFAULT_REQUEST_TYPE);
        String requestId = firstSafeValue(
                request.getHeader(REQUEST_ID_HEADER),
                inboundBaggage.getEntryValue(REQUEST_ID_BAGGAGE),
                UUID.randomUUID().toString());
        String requestTimestamp = firstTimestamp(
                request.getHeader(REQUEST_TIMESTAMP_HEADER),
                inboundBaggage.getEntryValue(REQUEST_TIMESTAMP_BAGGAGE));

        String traceId = inboundSpan.isValid()
                ? inboundSpan.getTraceId()
                : ID_GENERATOR.generateTraceId();
        SpanContext requestSpan = SpanContext.create(
                traceId,
                ID_GENERATOR.generateSpanId(),
                inboundSpan.isValid() ? inboundSpan.getTraceFlags() : TraceFlags.getSampled(),
                inboundSpan.isValid() ? inboundSpan.getTraceState() : TraceState.getDefault());

        response.setHeader(REQUEST_TYPE_HEADER, requestType);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(REQUEST_TIMESTAMP_HEADER, requestTimestamp);
        response.setHeader(TRACE_ID_HEADER, traceId);

        Baggage baggage = Baggage.builder()
                .put(REQUEST_TYPE_BAGGAGE, requestType)
                .put(REQUEST_ID_BAGGAGE, requestId)
                .put(REQUEST_TIMESTAMP_BAGGAGE, requestTimestamp)
                .build();
        Context requestContext = ScoreActivityTracing.requestContext(
                        Context.current(),
                        requestSpan,
                        inboundSpan.isValid() ? inboundSpan.getSpanId() : null,
                        ScoreHttpRequestCapture.capture(request))
                .with(baggage);
        try (Scope ignored = requestContext.makeCurrent()) {
            filterChain.doFilter(request, response);
        }
    }

    private String firstTimestamp(String headerCandidate, String baggageCandidate) {
        String timestamp = timestamp(headerCandidate);
        if (timestamp != null) {
            return timestamp;
        }
        timestamp = timestamp(baggageCandidate);
        return timestamp != null ? timestamp : Instant.now(clock).toString();
    }

    private static String timestamp(String candidate) {
        if (candidate != null) {
            try {
                return Instant.parse(candidate.strip()).toString();
            } catch (DateTimeParseException ignored) {
                // Try the next trusted correlation source.
            }
        }
        return null;
    }

    private static String firstSafeValue(String headerCandidate, String baggageCandidate, String fallback) {
        String value = safeValue(headerCandidate);
        if (value != null) {
            return value;
        }
        value = safeValue(baggageCandidate);
        return value != null ? value : fallback;
    }

    private static String safeValue(String candidate) {
        if (candidate == null) {
            return null;
        }
        String stripped = candidate.strip();
        return candidate.equals(stripped) && SAFE_VALUE.matcher(stripped).matches()
                ? stripped
                : null;
    }
}

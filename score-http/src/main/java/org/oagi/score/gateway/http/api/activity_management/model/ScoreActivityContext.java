package org.oagi.score.gateway.http.api.activity_management.model;

/** OpenTelemetry trace and request metadata that correlate distributed SCORE activities. */
public record ScoreActivityContext(
        String traceId,
        String spanId,
        String parentSpanId,
        String traceFlags,
        String traceState,
        String activityStartedAt,
        String requestType,
        String requestId,
        String requestTimestamp,
        ScoreHttpRequest httpRequest) {

    public ScoreActivityContext(
            String traceId,
            String spanId,
            String parentSpanId,
            String traceFlags,
            String traceState,
            String activityStartedAt,
            String requestType,
            String requestId,
            String requestTimestamp) {
        this(traceId, spanId, parentSpanId, traceFlags, traceState, activityStartedAt,
                requestType, requestId, requestTimestamp, null);
    }

    public ScoreActivityContext(String traceId, String spanId) {
        this(traceId, spanId, null, null, null, null, null, null, null, null);
    }

    public ScoreActivityContext(
            String traceId,
            String spanId,
            String parentSpanId,
            String requestType,
            String requestId,
            String requestTimestamp) {
        this(traceId, spanId, parentSpanId, null, null, null,
                requestType, requestId, requestTimestamp, null);
    }

    public ScoreActivityContext(
            String traceId,
            String spanId,
            String parentSpanId,
            String activityStartedAt,
            String requestType,
            String requestId,
            String requestTimestamp) {
        this(traceId, spanId, parentSpanId, null, null, activityStartedAt,
                requestType, requestId, requestTimestamp, null);
    }

    public ScoreActivityContext(
            String traceId,
            String spanId,
            String requestType,
            String requestId,
            String requestTimestamp) {
        this(traceId, spanId, null, null, null, null,
                requestType, requestId, requestTimestamp, null);
    }

    public static ScoreActivityContext empty() {
        return new ScoreActivityContext(
                null, null, null, null, null, null, null, null, null, null);
    }

    public ScoreActivityContext withSpanId(String spanId) {
        return new ScoreActivityContext(
                traceId, spanId, parentSpanId, traceFlags, traceState, activityStartedAt,
                requestType, requestId, requestTimestamp, httpRequest);
    }
}

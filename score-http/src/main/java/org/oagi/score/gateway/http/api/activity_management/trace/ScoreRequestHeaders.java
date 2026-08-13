package org.oagi.score.gateway.http.api.activity_management.trace;

/** SCORE request headers and their OpenTelemetry baggage/attribute names. */
public final class ScoreRequestHeaders {

    public static final String REQUEST_TYPE_HEADER = "X-Score-Request-Type";
    public static final String REQUEST_ID_HEADER = "X-Score-Request-Id";
    public static final String REQUEST_TIMESTAMP_HEADER = "X-Score-Request-Timestamp";
    public static final String WEB_VERSION_HEADER = "X-Score-Web-Version";
    public static final String TRACE_ID_HEADER = "X-Score-Trace-Id";

    public static final String REQUEST_TYPE_BAGGAGE = "score.request.type";
    public static final String REQUEST_ID_BAGGAGE = "score.request.id";
    public static final String REQUEST_TIMESTAMP_BAGGAGE = "score.request.timestamp";

    private ScoreRequestHeaders() {
    }
}

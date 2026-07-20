package org.oagi.score.gateway.http.api.ai_management.model;

/** Cross-instance stop signal for a request generation. */
public record AiRequestStopSignal(String requestId, long generation) {
}

package org.oagi.score.gateway.http.api.ai_management.model;

/** Tool call awaiting completion and observation persistence. */
public record AiPendingTool(
        String id,
        String name,
        Object arguments,
        AiObservationAccumulator observations,
        long sequence) {
}

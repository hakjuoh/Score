package org.oagi.score.gateway.http.api.ai_management.model;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;

/** Cancellation response and whether the owning worker must be signalled. */
public record AiCancellationOutcome(
        AiCancellationResponse response,
        boolean signalOwner) {
}

package org.oagi.score.gateway.http.api.ai_management.model;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMutationConfirmationDecisionResponse;
import org.springframework.http.HttpStatus;

/**
 * Service result for an approve or deny mutation-confirmation decision.
 *
 * @param response stable response payload returned to the client
 * @param status HTTP status matching the lifecycle disposition
 */
public record AiMutationDecision(
        AiMutationConfirmationDecisionResponse response,
        HttpStatus status) {
}

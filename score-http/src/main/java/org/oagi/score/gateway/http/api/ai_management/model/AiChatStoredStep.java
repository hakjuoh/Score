package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;

/**
 * Database identity assigned to a persisted AI trajectory step.
 *
 * @param id internal trajectory-step identifier
 * @param sequence zero-based sequence within the conversation
 * @param createdAt persistence timestamp
 */
public record AiChatStoredStep(AiChatStepId id, long sequence, Instant createdAt) {
}

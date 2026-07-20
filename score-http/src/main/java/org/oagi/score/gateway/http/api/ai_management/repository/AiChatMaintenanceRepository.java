package org.oagi.score.gateway.http.api.ai_management.repository;

import java.time.Instant;

/** Unscoped maintenance operations for retained AI conversation data. */
public interface AiChatMaintenanceRepository {

    int deleteExpiredConversations(Instant cutoff);

    int expireMutationConfirmations(Instant now);
}

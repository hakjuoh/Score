package org.oagi.score.gateway.http.api.ai_management.repository;

import java.time.Instant;
import java.util.List;

/** Unscoped maintenance operations for retained AI conversation data. */
public interface AiChatMaintenanceRepository {

    List<String> findExpiredConversationGuids(Instant cutoff);

    int deleteExpiredConversations(Instant cutoff);

    int expireMutationConfirmations(Instant now);
}

package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;
import java.util.List;

/** Owner-safe, root-scoped approval request for one or more exact mutation invocations. */
public record AiMutationApprovalBatchNotice(
        String batchId,
        String requestId,
        String rootConversationId,
        boolean parallel,
        Instant expiresAt,
        List<Item> items) {

    public AiMutationApprovalBatchNotice {
        items = items != null ? List.copyOf(items) : List.of();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("An approval batch requires at least one item.");
        }
    }

    public record Item(
            String confirmationRequestId,
            String toolName,
            String argumentsSummary,
            String agentId,
            String agentLabel) {
    }
}

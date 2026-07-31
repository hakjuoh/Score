package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeApprovalDecisionRequest;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Retains short-lived approval decisions so identical client retries are idempotent. */
final class AiApprovalDecisionLedger {

    private static final int MAX_DECIDED_BATCHES = 10_000;
    private static final Duration DECIDED_BATCH_RETENTION = Duration.ofMinutes(2);

    private final Map<String, DecidedBatch> decisions = new ConcurrentHashMap<>();

    boolean contains(String batchId) {
        return decisions.containsKey(batchId);
    }

    AiChangeApprovalCoordinator.DecisionAcknowledgement replay(
            ScoreUser requester,
            AiChangeApprovalDecisionRequest command,
            Map<String, String> requested) {
        Instant now = Instant.now();
        purgeExpired(now);
        DecidedBatch decided = decisions.get(command.batchId());
        if (decided == null || !now.isBefore(decided.expiresAt)) {
            if (decided != null) {
                decisions.remove(command.batchId(), decided);
            }
            throw new IllegalArgumentException("The change approval batch is no longer pending.");
        }
        if (!decided.appUserId.equals(requester.userId().value().toString())) {
            throw new AccessDeniedException("The change approval batch belongs to another user.");
        }
        if (!decided.requestId.equals(command.requestId())
                || !decided.rootConversationId.equals(command.conversationId())) {
            throw new IllegalArgumentException("The change approval batch identity does not match.");
        }
        if (!decided.decisions.equals(requested)) {
            throw new IllegalArgumentException(
                    "The change approval batch was already answered with different decisions.");
        }
        return decided.acknowledgement;
    }

    void remember(String batchId, String appUserId, String rootConversationId,
                  String requestId, Map<String, String> requested,
                  AiChangeApprovalCoordinator.DecisionAcknowledgement acknowledgement) {
        Instant now = Instant.now();
        purgeExpired(now);
        if (decisions.size() >= MAX_DECIDED_BATCHES) {
            decisions.entrySet().stream()
                    .min(Map.Entry.comparingByValue(
                            Comparator.comparing(DecidedBatch::expiresAt)))
                    .ifPresent(entry -> decisions.remove(entry.getKey(), entry.getValue()));
        }
        decisions.put(batchId, new DecidedBatch(
                appUserId, rootConversationId, requestId, Map.copyOf(requested),
                acknowledgement, now.plus(DECIDED_BATCH_RETENTION)));
    }

    private void purgeExpired(Instant now) {
        decisions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
    }

    private record DecidedBatch(
            String appUserId,
            String rootConversationId,
            String requestId,
            Map<String, String> decisions,
            AiChangeApprovalCoordinator.DecisionAcknowledgement acknowledgement,
            Instant expiresAt) {
    }
}

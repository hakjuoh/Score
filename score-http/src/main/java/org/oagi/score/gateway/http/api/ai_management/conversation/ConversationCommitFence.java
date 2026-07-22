package org.oagi.score.gateway.http.api.ai_management.conversation;

/** Atomic stale/cancelled-result fence supplied by request lifecycle infrastructure. */
@FunctionalInterface
public interface ConversationCommitFence {
    boolean commitResult(String requestId, Runnable atomicWrite);
}

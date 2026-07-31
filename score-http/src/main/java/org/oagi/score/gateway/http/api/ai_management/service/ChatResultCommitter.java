package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationResultCommitter;

import java.util.concurrent.CancellationException;

/** Shared cancellation-aware fence for committing any chat request result. */
final class ChatResultCommitter {

    private final ConversationResultCommitter resultCommitter;
    private final AiRequestRegistry requests;

    ChatResultCommitter(ConversationResultCommitter resultCommitter,
                        AiRequestRegistry requests) {
        this.resultCommitter = resultCommitter;
        this.requests = requests;
    }

    void commit(String requestId, Runnable persistence) {
        if (resultCommitter != null) {
            resultCommitter.commit(requestId, persistence);
        } else if (requests != null) {
            if (!requests.commitResult(requestId, persistence)) {
                throw new CancellationException(
                        "The assistant request stopped before its result was committed.");
            }
        } else {
            persistence.run();
        }
    }

    void rejectDiscarded(String requestId) {
        if (Thread.currentThread().isInterrupted()
                || requests != null && requests.shouldDiscardResult(requestId)) {
            throw new CancellationException("The assistant request was interrupted.");
        }
    }
}

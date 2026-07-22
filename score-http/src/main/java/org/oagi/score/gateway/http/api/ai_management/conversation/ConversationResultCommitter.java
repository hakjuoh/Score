package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Sole fenced commit use case for accepted conversation results. */
@Component
public final class ConversationResultCommitter {

    private final ConversationCommitFence requests;

    public ConversationResultCommitter(ConversationCommitFence requests) {
        this.requests = Objects.requireNonNull(requests);
    }

    public void commit(String requestId, Runnable atomicWrite) {
        Objects.requireNonNull(atomicWrite, "atomicWrite");
        if (!requests.commitResult(requestId, atomicWrite)) {
            throw new CancellationException(
                    "The assistant request stopped before its result was committed.");
        }
    }
}

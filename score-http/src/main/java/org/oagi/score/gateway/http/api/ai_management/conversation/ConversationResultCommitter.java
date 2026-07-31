package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Sole fenced commit use case for accepted conversation results. */
@Component
public final class ConversationResultCommitter {

    private final ConversationCommitFence requests;
    private final TransactionTemplate transactions;

    public ConversationResultCommitter(ConversationCommitFence requests,
                                       PlatformTransactionManager transactionManager) {
        this.requests = Objects.requireNonNull(requests);
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
    }

    public void commit(String requestId, Runnable persistence) {
        Objects.requireNonNull(persistence, "persistence");
        Runnable transactionalWrite = () -> transactions.executeWithoutResult(
                ignored -> persistence.run());
        if (!requests.commitResult(requestId, transactionalWrite)) {
            throw new CancellationException(
                    "The assistant request stopped before its result was committed.");
        }
    }
}

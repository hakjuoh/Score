package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationResultCommitterTest {

    @Test
    void runsAcceptedPersistenceInOneTransactionAndRollsItBackOnFailure() {
        TestTransactionManager transactions = new TestTransactionManager();
        ConversationResultCommitter committer = new ConversationResultCommitter(
                (requestId, write) -> {
                    write.run();
                    return true;
                }, transactions);
        AtomicBoolean transactionActive = new AtomicBoolean();

        assertThatThrownBy(() -> committer.commit("request-1", () -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("write failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("write failed");

        assertThat(transactionActive).isTrue();
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void rejectedPersistenceStartsNoTransactionAndExecutesNoWrites() {
        TestTransactionManager transactions = new TestTransactionManager();
        ConversationResultCommitter committer = new ConversationResultCommitter(
                (requestId, write) -> false, transactions);
        AtomicBoolean executed = new AtomicBoolean();

        assertThatThrownBy(() -> committer.commit(
                "request-1", () -> executed.set(true)))
                .isInstanceOf(CancellationException.class);

        assertThat(executed).isFalse();
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isZero();
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}

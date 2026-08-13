package org.oagi.score.gateway.http.api.activity_management.service;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivityHandlerBinding;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityActor;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.trace.ScoreActivityTracing;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoreActivityAspectTest {

    private AnnotationConfigApplicationContext context;
    private TestOperation operation;
    private TestActivityHandler handler;
    private CapturingPublisher publisher;
    private RecordingTransactionManager transactions;
    private OuterOperation outerOperation;
    private UnboundOperation unboundOperation;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        operation = context.getBean(TestOperation.class);
        handler = context.getBean(TestActivityHandler.class);
        publisher = context.getBean(CapturingPublisher.class);
        transactions = context.getBean(RecordingTransactionManager.class);
        outerOperation = context.getBean(OuterOperation.class);
        unboundOperation = context.getBean(UnboundOperation.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void publishesSuccessOnlyAfterTheTransactionCommits() {
        assertThat(operation.run()).isEqualTo("result");

        assertThat(transactions.commits).isEqualTo(1);
        assertThat(handler.successObservedAfterCommit).isTrue();
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement()
                .extracting(ScoreActivityEvent::outcome)
                .isEqualTo(ScoreActivityEvent.SUCCEEDED);
    }

    @Test
    void convertsACommitFailureToActivityFailureAndPreservesTheException() {
        transactions.failCommit = true;

        assertThatThrownBy(operation::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("commit failed");

        assertThat(handler.failure).isInstanceOf(IllegalStateException.class);
        assertThat(publisher.immediateEvents).singleElement()
                .extracting(ScoreActivityEvent::outcome)
                .isEqualTo(ScoreActivityEvent.FAILED);
        assertThat(publisher.transactionalEvents).isEmpty();
    }

    @Test
    void publishesOrdinaryFailureOnlyAfterTheTransactionRollsBack() {
        assertThatThrownBy(operation::fail)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operation failed");

        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(handler.failureObservedAfterRollback).isTrue();
        assertThat(publisher.immediateEvents).singleElement()
                .extracting(ScoreActivityEvent::outcome)
                .isEqualTo(ScoreActivityEvent.FAILED);
    }

    @Test
    void preparationFailureCannotChangeTheBusinessResult() {
        handler.failStart = true;

        assertThat(operation.run()).isEqualTo("result");

        assertThat(transactions.commits).isEqualTo(1);
        assertThat(handler.startCalls).isEqualTo(1);
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).isEmpty();
    }

    @Test
    void enabledCheckFailureCannotChangeTheBusinessResult() {
        publisher.failEnabledCheck = true;

        assertThat(operation.run()).isEqualTo("result");

        assertThat(transactions.commits).isEqualTo(1);
        assertThat(handler.startCalls).isZero();
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).isEmpty();
    }

    @Test
    void disabledEventDeliveryDoesNotAffectANotAppliedBusinessResult() {
        publisher.enabled = false;

        assertThat(operation.notApplied()).isFalse();

        assertThat(handler.successCalls).isZero();
        assertThat(handler.failureCalls).isZero();
        assertThat(handler.startCalls).isZero();
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).isEmpty();
    }

    @Test
    void missingMethodAndClassHandlersFailEvenWhenDeliveryIsDisabled() {
        publisher.enabled = false;

        assertThatThrownBy(unboundOperation::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No SCORE activity handler");

        assertThat(unboundOperation.invoked).isFalse();
    }

    @Test
    void nestedSuccessBecomesAFailureWhenTheSurroundingTransactionRollsBack() {
        assertThatThrownBy(outerOperation::runThenFail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outer operation failed");

        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement()
                .extracting(ScoreActivityEvent::outcome)
                .isEqualTo(ScoreActivityEvent.FAILED);
    }

    @Test
    void nestedSuccessIsPublishedOnceAfterTheSurroundingTransactionCommits() {
        outerOperation.run();

        assertThat(transactions.commits).isEqualTo(1);
        assertThat(handler.successCalls).isEqualTo(1);
        assertThat(handler.failureCalls).isZero();
        assertThat(handler.successSpanId).isEqualTo(handler.startSpanId).isNotEqualTo("0000000000000000");
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement()
                .extracting(ScoreActivityEvent::outcome)
                .isEqualTo(ScoreActivityEvent.SUCCEEDED);
    }

    @Test
    void nestedNotAppliedResultProducesOnlyOneFailureWhenTheSurroundingTransactionRollsBack() {
        assertThatThrownBy(outerOperation::runNotAppliedThenFail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outer operation failed");

        assertThat(handler.successCalls).isZero();
        assertThat(handler.failureCalls).isEqualTo(1);
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement()
                .extracting(ScoreActivityEvent::outcome)
                .isEqualTo(ScoreActivityEvent.FAILED);
    }

    @Test
    void nestedSuccessBecomesAFailureWhenTheSurroundingCommitStatusIsUnknown() {
        transactions.failCommitWithTransactionException = true;

        assertThatThrownBy(outerOperation::run)
                .isInstanceOf(TransactionSystemException.class)
                .hasMessage("commit status unknown");

        assertThat(handler.successCalls).isZero();
        assertThat(handler.failureCalls).isEqualTo(1);
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement()
                .extracting(ScoreActivityEvent::outcome)
                .isEqualTo(ScoreActivityEvent.FAILED);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        CapturingPublisher scoreActivityEventPublisher() {
            return new CapturingPublisher();
        }

        @Bean
        ScoreActivityEventRecorder scoreActivityEventRecorder() {
            return new ScoreActivityEventRecorder();
        }

        @Bean
        TestActivityHandler testActivityHandler(RecordingTransactionManager transactions) {
            return new TestActivityHandler(transactions);
        }

        @Bean
        ScoreActivityAspect scoreActivityAspect(
                ApplicationContext applicationContext,
                CapturingPublisher publisher,
                ScoreActivityEventRecorder recorder,
                ScoreActivityTracing tracing) {
            return new ScoreActivityAspect(applicationContext, publisher, recorder, tracing);
        }

        @Bean
        ScoreActivityTracing scoreActivityTracing() {
            return new ScoreActivityTracing();
        }

        @Bean
        TestOperation testOperation() {
            return new TestOperation();
        }

        @Bean
        OuterOperation outerOperation(TestOperation operation) {
            return new OuterOperation(operation);
        }

        @Bean
        UnboundOperation unboundOperation() {
            return new UnboundOperation();
        }
    }

    @ScoreActivityHandlerBinding(TestActivityHandler.class)
    static class TestOperation {

        @Transactional
        @ScoreActivity(category = "test", action = "run")
        public String run() {
            return "result";
        }

        @Transactional
        @ScoreActivity(category = "test", action = "run")
        public String fail() {
            throw new IllegalArgumentException("operation failed");
        }

        @Transactional
        @ScoreActivity(category = "test", action = "run")
        public boolean notApplied() {
            return false;
        }
    }

    static class OuterOperation {

        private final TestOperation operation;

        OuterOperation(TestOperation operation) {
            this.operation = operation;
        }

        @Transactional
        public void runThenFail() {
            operation.run();
            throw new IllegalStateException("outer operation failed");
        }

        @Transactional
        public void runNotAppliedThenFail() {
            operation.notApplied();
            throw new IllegalStateException("outer operation failed");
        }

        @Transactional
        public void run() {
            operation.run();
        }
    }

    static class UnboundOperation {
        private boolean invoked;

        @ScoreActivity(category = "test", action = "unbound")
        public void run() {
            invoked = true;
        }
    }

    static final class TestActivityHandler implements ScoreActivityHandler {

        private final RecordingTransactionManager transactions;
        private boolean successObservedAfterCommit;
        private Throwable failure;
        private boolean failureObservedAfterRollback;
        private boolean failStart;
        private int successCalls;
        private int failureCalls;
        private int startCalls;
        private String startSpanId;
        private String successSpanId;

        TestActivityHandler(RecordingTransactionManager transactions) {
            this.transactions = transactions;
        }

        @Override
        public boolean supports(ScoreActivityInvocation invocation) {
            return "test.run".equals(invocation.name());
        }

        @Override
        public ScoreActivityExecution start(ScoreActivityInvocation invocation) {
            startCalls++;
            if (failStart) {
                throw new IllegalStateException("preparation failed");
            }
            startSpanId = Span.current().getSpanContext().getSpanId();
            return new ScoreActivityExecution() {
                @Override
                public List<ScoreActivityEvent> succeeded(Object result) {
                    successCalls++;
                    successSpanId = Span.current().getSpanContext().getSpanId();
                    successObservedAfterCommit = transactions.commits == 1
                            && !TransactionSynchronizationManager.isActualTransactionActive();
                    return List.of(event(Boolean.FALSE.equals(result)
                            ? ScoreActivityEvent.FAILED
                            : ScoreActivityEvent.SUCCEEDED));
                }

                @Override
                public List<ScoreActivityEvent> failed(Throwable failure) {
                    failureCalls++;
                    TestActivityHandler.this.failure = failure;
                    failureObservedAfterRollback = transactions.rollbacks == 1
                            && !TransactionSynchronizationManager.isActualTransactionActive();
                    return List.of(event(ScoreActivityEvent.FAILED));
                }
            };
        }
    }

    static final class CapturingPublisher implements ScoreActivityEventPublisher {

        private final List<ScoreActivityEvent> transactionalEvents = new ArrayList<>();
        private final List<ScoreActivityEvent> immediateEvents = new ArrayList<>();
        private boolean failEnabledCheck;
        private boolean enabled = true;

        @Override
        public boolean isEnabled() {
            if (failEnabledCheck) {
                throw new IllegalStateException("publisher health unavailable");
            }
            return enabled;
        }

        @Override
        public void publish(ScoreActivityEvent event) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        transactionalEvents.add(event);
                    }
                });
            } else {
                transactionalEvents.add(event);
            }
        }

        @Override
        public void publishImmediately(ScoreActivityEvent event) {
            immediateEvents.add(event);
        }
    }

    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final ThreadLocal<TestTransaction> currentTransaction = new ThreadLocal<>();
        private int commits;
        private int rollbacks;
        private boolean failCommit;
        private boolean failCommitWithTransactionException;

        @Override
        protected Object doGetTransaction() {
            TestTransaction current = currentTransaction.get();
            return current != null ? current : new TestTransaction();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TestTransaction) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TestTransaction testTransaction = (TestTransaction) transaction;
            testTransaction.active = true;
            currentTransaction.set(testTransaction);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommitWithTransactionException) {
                throw new TransactionSystemException("commit status unknown");
            }
            if (failCommit) {
                throw new IllegalStateException("commit failed");
            }
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            ((TestTransaction) transaction).active = false;
            currentTransaction.remove();
        }

        private static final class TestTransaction {
            private boolean active;
        }
    }

    private static ScoreActivityEvent event(String outcome) {
        return new ScoreActivityEvent(
                ScoreActivityEvent.SCHEMA_VERSION,
                "cc2f52bb-cffa-42c6-b673-6e25aba5ebda",
                Instant.parse("2026-08-06T12:00:00Z"),
                "test.run",
                "SCORE_HTTP_API",
                outcome,
                new ScoreActivityActor("7", "developer"),
                List.of(new ScoreActivityTarget("TEST", "1", null, null, "PRIMARY")),
                Map.of(),
                ScoreActivityContext.empty());
    }
}

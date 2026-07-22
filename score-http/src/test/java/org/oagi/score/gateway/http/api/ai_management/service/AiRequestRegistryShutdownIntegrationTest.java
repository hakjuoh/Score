package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.execution.AiRequestStateStore;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiRequestRegistryShutdownIntegrationTest {

    private final ScoreUser user = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
            null, false, List.of());

    @Test
    void contextCloseTerminalizesAndInterruptsActiveWorkBeforeClosingChatExecutor() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(ShutdownContextConfiguration.class, AiRequestRegistry.class);
        context.refresh();
        AiRequestRegistry registry = context.getBean(AiRequestRegistry.class);
        ShutdownProbe probe = context.getBean(ShutdownProbe.class);
        ExecutorService executor = context.getBean("scoreAiChatExecutor", ExecutorService.class);
        AiRequestRegistry.Entry entry = registry.register(
                ShutdownProbe.REQUEST_ID, "conversation-context-close", user,
                Instant.now().plusSeconds(60));

        executor.execute(() -> {
            assertThat(registry.start(entry)).isTrue();
            probe.worker.set(Thread.currentThread());
            probe.workerStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                probe.workerObservedInterrupt.set(true);
            }
        });
        assertThat(probe.workerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        context.close();

        assertThat(probe.executorCloseCalled).isTrue();
        assertThat(probe.terminalBeforeExecutorClose).isTrue();
        assertThat(probe.interruptSignalledBeforeExecutorClose).isTrue();
        assertThat(registry.status(ShutdownProbe.REQUEST_ID, user))
                .extracting(status -> status.status(), status -> status.statusReason())
                .containsExactly("FAILED", "WORKER_INSTANCE_SHUTDOWN");
    }

    @Configuration(proxyBeanMethods = false)
    static class ShutdownContextConfiguration {

        @Bean
        AiRequestStateStore requestStateStore() {
            return AiRequestStateStore.inMemory();
        }

        @Bean
        ShutdownProbe shutdownProbe() {
            return new ShutdownProbe();
        }

        @Bean(name = "scoreAiChatExecutor", destroyMethod = "close")
        ExecutorService scoreAiChatExecutor(AiRequestStateStore stateStore, ShutdownProbe probe) {
            return new ShutdownTrackingExecutor(stateStore, probe);
        }

        @Bean(name = "scoreAiLifecycleScheduler", destroyMethod = "shutdownNow")
        ScheduledExecutorService scoreAiLifecycleScheduler() {
            return Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("shutdown-order-test-", 0).daemon(true).factory());
        }
    }

    static final class ShutdownProbe {
        static final String REQUEST_ID = "request-context-close";

        final CountDownLatch workerStarted = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final AtomicBoolean workerObservedInterrupt = new AtomicBoolean();
        volatile boolean executorCloseCalled;
        volatile boolean terminalBeforeExecutorClose;
        volatile boolean interruptSignalledBeforeExecutorClose;
    }

    static final class ShutdownTrackingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        private final AiRequestStateStore stateStore;
        private final ShutdownProbe probe;

        ShutdownTrackingExecutor(AiRequestStateStore stateStore, ShutdownProbe probe) {
            this.stateStore = stateStore;
            this.probe = probe;
        }

        @Override
        public void close() {
            probe.executorCloseCalled = true;
            probe.terminalBeforeExecutorClose = stateStore.withRequestLock(
                    ShutdownProbe.REQUEST_ID, storage -> {
                        var state = storage.get(ShutdownProbe.REQUEST_ID);
                        return state != null && state.terminal()
                                && "WORKER_INSTANCE_SHUTDOWN".equals(state.statusReason());
                    });
            Thread worker = probe.worker.get();
            probe.interruptSignalledBeforeExecutorClose = probe.workerObservedInterrupt.get()
                    || worker != null && worker.isInterrupted();
            delegate.shutdownNow();
        }

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
        @Override public void execute(Runnable command) { delegate.execute(command); }
    }
}

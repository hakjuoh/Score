package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.service.AsyncScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.OpenTelemetryScoreActivityContextProvider;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityAspect;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventRecorder;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventSink;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.CreateOagisBodResponse;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreComponentActivityHierarchyTest {

    private static final ScoreUser REQUESTER = new ScoreUser(
            new UserId(BigInteger.valueOf(7)), "developer", "Developer", null, false,
            List.of(ScoreRole.DEVELOPER));

    @Test
    void bodCascadeKeepsOneTraceAndParentsEveryChildToTheCompositeActivity() {
        CapturingSink sink = new CapturingSink();
        try (AsyncScoreActivityEventPublisher publisher = publisher(sink)) {
            BodCommands commands = commands(publisher);
            inTransaction(() -> commands.createBod(REQUESTER), TransactionSynchronization.STATUS_COMMITTED);
        }

        assertThat(sink.events).hasSize(3);
        ScoreActivityEvent root = event(sink.events, "oagis-bod.create");
        assertThat(root.outcome()).isEqualTo(ScoreActivityEvent.SUCCEEDED);
        assertThat(List.of(event(sink.events, "acc.create"), event(sink.events, "ascc.create")))
                .allSatisfy(child -> {
                    assertThat(child.context().traceId()).isEqualTo(root.context().traceId());
                    assertThat(child.context().parentSpanId()).isEqualTo(root.context().spanId());
                });
    }

    @Test
    void rolledBackCascadePublishesOnlyFailureOutcomesThroughTheAsyncPublisher() {
        CapturingSink sink = new CapturingSink();
        try (AsyncScoreActivityEventPublisher publisher = publisher(sink)) {
            BodCommands commands = commands(publisher);
            assertThatThrownBy(() -> inTransaction(
                    () -> commands.createBodThenFail(REQUESTER),
                    TransactionSynchronization.STATUS_ROLLED_BACK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("BOD failed");
        }

        assertThat(sink.events).hasSize(2)
                .allSatisfy(event -> assertThat(event.outcome()).isEqualTo(ScoreActivityEvent.FAILED));
        ScoreActivityEvent root = event(sink.events, "oagis-bod.create");
        ScoreActivityEvent child = event(sink.events, "acc.create");
        assertThat(child.context().traceId()).isEqualTo(root.context().traceId());
        assertThat(child.context().parentSpanId()).isEqualTo(root.context().spanId());
        assertThat(child.properties()).containsEntry("errorCode", "INTERNAL_ERROR");
    }

    private static BodCommands commands(AsyncScoreActivityEventPublisher publisher) {
        CoreComponentActivityTargetResolver targets = mock(CoreComponentActivityTargetResolver.class);
        when(targets.resolve(anyString(), any(), any())).thenAnswer(invocation ->
                target(invocation.getArgument(0), invocation.getArgument(2)));
        when(targets.resolveOrReference(anyString(), any(), any())).thenAnswer(invocation ->
                target(invocation.getArgument(0), invocation.getArgument(2)));
        CoreComponentActivityEventFactory events = new CoreComponentActivityEventFactory(
                new ScoreActivityEventFactory(
                        Clock.systemUTC(), new OpenTelemetryScoreActivityContextProvider()),
                targets);
        ScoreActivityFailureClassifier failures = new ScoreActivityFailureClassifier();
        CoreComponentActivityHandler lifecycle = new CoreComponentActivityHandler(events, failures);
        CoreComponentNestedActivityHandler nested = new CoreComponentNestedActivityHandler(
                events, targets, failures);
        CoreComponentOperationActivityHandler composite = new CoreComponentOperationActivityHandler(
                events, targets, mock(RepositoryFactory.class), failures);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(CoreComponentActivityHandler.class)).thenReturn(lifecycle);
        when(applicationContext.getBean(CoreComponentNestedActivityHandler.class)).thenReturn(nested);
        when(applicationContext.getBean(CoreComponentOperationActivityHandler.class)).thenReturn(composite);
        ScoreActivityAspect aspect = new ScoreActivityAspect(
                applicationContext, publisher, new ScoreActivityEventRecorder());

        AspectJProxyFactory executorProxyFactory = new AspectJProxyFactory(new CoreComponentActivityExecutor());
        executorProxyFactory.addAspect(aspect);
        CoreComponentActivityExecutor executor = executorProxyFactory.getProxy();

        AspectJProxyFactory commandProxyFactory = new AspectJProxyFactory(new BodCommands(executor));
        commandProxyFactory.addAspect(aspect);
        return commandProxyFactory.getProxy();
    }

    private static void inTransaction(Runnable operation, int completionStatus) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            operation.run();
        } finally {
            List<TransactionSynchronization> synchronizations = new ArrayList<>(
                    TransactionSynchronizationManager.getSynchronizations());
            synchronizations.forEach(sync -> sync.afterCompletion(completionStatus));
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static AsyncScoreActivityEventPublisher publisher(CapturingSink sink) {
        return new AsyncScoreActivityEventPublisher(sink, 1, 32, Duration.ofSeconds(2));
    }

    private static ScoreActivityEvent event(List<ScoreActivityEvent> events, String name) {
        return events.stream().filter(event -> name.equals(event.name())).findFirst().orElseThrow();
    }

    private static ScoreActivityTarget target(String category, Object id) {
        String type = "dt-sc".equals(category) ? "DT_SC" : category.toUpperCase();
        return ScoreActivityTarget.primary(type, id,
                new Guid("0123456789abcdef0123456789abcdef"), category + " name");
    }

    public static class BodCommands {
        private final CoreComponentActivityExecutor executor;

        BodCommands(CoreComponentActivityExecutor executor) {
            this.executor = executor;
        }

        @ScoreActivity(category = "oagis-bod", action = "create", operation = "generate-bod",
                handler = CoreComponentOperationActivityHandler.class)
        public CreateOagisBodResponse createBod(ScoreUser requester) {
            executor.createAcc(requester, () -> new AccManifestId(BigInteger.valueOf(41)));
            executor.createAscc(requester, () -> new AsccManifestId(BigInteger.valueOf(42)));
            return new CreateOagisBodResponse(
                    List.of(new AsccpManifestId(BigInteger.valueOf(43))));
        }

        @ScoreActivity(category = "oagis-bod", action = "create", operation = "generate-bod",
                handler = CoreComponentOperationActivityHandler.class)
        public void createBodThenFail(ScoreUser requester) {
            executor.createAcc(requester, () -> new AccManifestId(BigInteger.valueOf(41)));
            throw new IllegalStateException("BOD failed");
        }
    }

    private static final class CapturingSink implements ScoreActivityEventSink {
        private final List<ScoreActivityEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public String type() {
            return "test";
        }

        @Override
        public void write(ScoreActivityEvent event) {
            events.add(event);
        }
    }
}

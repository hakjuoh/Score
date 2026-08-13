package org.oagi.score.gateway.http.api.cc_management.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.account_management.model.UserSummaryRecord;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityException;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventRecorder;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityAspect;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.acc.AccCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.acc.AccUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccDetailsRecord;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.acc.OagisComponentType;
import org.oagi.score.gateway.http.api.cc_management.repository.AccCommandRepository;
import org.oagi.score.gateway.http.api.cc_management.repository.AccQueryRepository;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityEventFactory;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityHandler;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityTargetResolver;
import org.oagi.score.gateway.http.api.log_management.model.LogId;
import org.oagi.score.gateway.http.api.log_management.repository.LogCommandRepository;
import org.oagi.score.gateway.http.api.library_management.model.LibraryId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseState;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseSummaryRecord;
import org.oagi.score.gateway.http.api.release_management.repository.ReleaseQueryRepository;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CcCommandServiceActivityEventTest {

    private final ScoreUser requester = new ScoreUser(
            new UserId(BigInteger.valueOf(7)),
            "administrator",
            "Administrator",
            null,
            false,
            List.of(ScoreRole.ADMINISTRATOR));
    private final AccManifestId manifestId = new AccManifestId(BigInteger.valueOf(42));
    private final AccSummaryRecord before = mock(AccSummaryRecord.class);
    private final AccDetailsRecord after = mock(AccDetailsRecord.class);
    private final AccQueryRepository query = mock(AccQueryRepository.class);
    private final AccCommandRepository command = mock(AccCommandRepository.class);
    private final LogCommandRepository logs = mock(LogCommandRepository.class);
    private final ReleaseQueryRepository releaseQuery = mock(ReleaseQueryRepository.class);
    private final RepositoryFactory repositories = mock(RepositoryFactory.class);
    private final CapturingPublisher publisher = new CapturingPublisher();
    private final CcCommandService targetService = new CcCommandService();
    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private CcCommandService service;

    @BeforeEach
    void setUp() {
        when(before.state()).thenReturn(CcState.WIP);
        when(before.componentType()).thenReturn(OagisComponentType.Base);
        when(before.objectClassTerm()).thenReturn("Invoice");
        when(before.accManifestId()).thenReturn(manifestId);
        when(before.guid()).thenReturn(new Guid("0123456789abcdef0123456789abcdef"));
        when(before.den()).thenReturn("Invoice. Details");
        when(after.accManifestId()).thenReturn(manifestId);
        when(after.guid()).thenReturn(new Guid("0123456789abcdef0123456789abcdef"));
        when(after.den()).thenReturn("Order. Details");
        when(query.getAccSummary(manifestId)).thenReturn(before);
        when(query.getAccDetails(manifestId)).thenReturn(after);
        when(repositories.accQueryRepository(requester)).thenReturn(query);
        when(repositories.accCommandRepository(requester)).thenReturn(command);
        when(repositories.logCommandRepository(requester)).thenReturn(logs);
        when(repositories.releaseQueryRepository(requester)).thenReturn(releaseQuery);
        ReflectionTestUtils.setField(targetService, "repositoryFactory", repositories);
        CoreComponentActivityEventFactory eventFactory = new CoreComponentActivityEventFactory(
                new ScoreActivityEventFactory(Clock.systemUTC(), ScoreActivityContext::empty),
                new CoreComponentActivityTargetResolver(repositories));
        when(applicationContext.getBean(CoreComponentActivityHandler.class))
                .thenReturn(new CoreComponentActivityHandler(
                        eventFactory, new ScoreActivityFailureClassifier()));
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(targetService);
        proxyFactory.addAspect(new ScoreActivityAspect(
                applicationContext, publisher, new ScoreActivityEventRecorder()));
        service = proxyFactory.getProxy();
    }

    @Test
    void successfulAccUpdatePublishesAfterCommitEventOnly() {
        when(command.update(manifestId, "Order", null, null, null, null, null)).thenReturn(true);
        LogId logId = new LogId(BigInteger.ONE);
        when(logs.create(org.mockito.ArgumentMatchers.eq(after),
                org.mockito.ArgumentMatchers.any(), anyString())).thenReturn(logId);

        assertThat(service.updateAcc(requester, request())).isTrue();

        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("acc.update");
            assertThat(event.outcome()).isEqualTo("SUCCEEDED");
            assertThat(event.targets().getFirst().id()).isEqualTo("42");
            assertThat(event.targets().getFirst().name()).isEqualTo("Invoice");
            assertThat(event.targets().getFirst().guid())
                    .isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(event.properties())
                    .containsEntry("requestedFields", List.of("objectClassTerm", "namespaceId"));
        });
        verify(command).updateLogId(manifestId, logId);
    }

    @Test
    void missingReleaseDuringAccCreationHasATargetNotFoundCode() {
        ReleaseId releaseId = new ReleaseId(BigInteger.valueOf(10));

        assertThatThrownBy(() -> service.createAcc(
                requester, AccCreateRequest.builder(releaseId).build()))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessage("Target release does not exist.");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("acc.create");
            assertThat(event.properties()).containsEntry("errorCode", "TARGET_NOT_FOUND");
        });
        verifyNoInteractions(command);
    }

    @Test
    void unpublishedReleaseDuringAccCreationKeepsItsExceptionAndHasAnInvalidStateCode() {
        ReleaseId releaseId = new ReleaseId(BigInteger.valueOf(10));
        when(releaseQuery.getReleaseSummary(releaseId)).thenReturn(new ReleaseSummaryRecord(
                releaseId,
                new LibraryId(BigInteger.ONE),
                "Working",
                ReleaseState.Draft));

        assertThatThrownBy(() -> service.createAcc(
                requester, AccCreateRequest.builder(releaseId).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("'Draft' Release cannot be modified.");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "INVALID_STATE"));
        verifyNoInteractions(command);
    }

    @Test
    void missingBaseAccDuringCreationHasATargetNotFoundCode() {
        ReleaseId releaseId = new ReleaseId(BigInteger.valueOf(10));
        AccManifestId basedAccManifestId = new AccManifestId(BigInteger.valueOf(43));
        when(releaseQuery.getReleaseSummary(releaseId)).thenReturn(new ReleaseSummaryRecord(
                releaseId,
                new LibraryId(BigInteger.ONE),
                "Published",
                ReleaseState.Published));

        assertThatThrownBy(() -> service.createAcc(requester, AccCreateRequest.builder(releaseId)
                .basedAccManifestId(basedAccManifestId)
                .build()))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessage("Base ACC does not exist.");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "TARGET_NOT_FOUND"));
        verifyNoInteractions(command);
    }

    @Test
    void validationFailurePublishesImmediatelyAndRethrows() {
        when(before.state()).thenReturn(CcState.Draft);

        assertThatThrownBy(() -> service.updateAcc(requester, request()))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessageContaining("Only ACCs in the 'WIP' state");

        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("acc.update");
            assertThat(event.outcome()).isEqualTo("FAILED");
            assertThat(event.properties()).containsEntry("errorCode", "INVALID_STATE");
        });
    }

    @Test
    void falseRepositoryResultPublishesNotAppliedFailure() {
        when(command.update(manifestId, "Order", null, null, null, null, null)).thenReturn(false);

        assertThat(service.updateAcc(requester, request())).isFalse();

        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("FAILED");
            assertThat(event.properties()).containsEntry("errorCode", "NOT_APPLIED");
        });
    }

    @Test
    void unexpectedRepositoryFailurePublishesInternalErrorAndRethrows() {
        when(query.getAccSummary(manifestId)).thenThrow(new RuntimeException("database details"));

        assertThatThrownBy(() -> service.updateAcc(requester, request()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database details");

        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("FAILED");
            assertThat(event.targets().getFirst().id()).isEqualTo("42");
            assertThat(event.properties())
                    .containsEntry("errorCode", "INTERNAL_ERROR")
                    .doesNotContainValue("database details");
        });
    }

    @Test
    void missingAccHasAStableTargetNotFoundCode() {
        when(query.getAccSummary(manifestId)).thenReturn(null);

        assertThatThrownBy(() -> service.updateAcc(requester, request()))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessage("The ACC does not exist.");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "TARGET_NOT_FOUND"));
    }

    @Test
    void nullRequestIsRecordedAsATargetlessValidationFailure() {
        assertThatThrownBy(() -> service.updateAcc(requester, null))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessage("'request' must not be null.");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.targets()).isEmpty();
            assertThat(event.properties()).containsEntry("errorCode", "VALIDATION_ERROR");
        });
    }

    @Test
    void nullManifestIdIsRecordedAsATargetlessValidationFailure() {
        AccUpdateRequest invalidRequest = new AccUpdateRequest(
                null, null, "Order", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateAcc(requester, invalidRequest))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessage("'accManifestId' must not be null.");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.targets()).isEmpty();
            assertThat(event.properties()).containsEntry("errorCode", "VALIDATION_ERROR");
        });
    }

    @Test
    void commandValidationFailureHasAStableValidationCode() {
        when(command.update(manifestId, "Order", null, null, null, null, null))
                .thenThrow(new IllegalArgumentException("invalid object class term"));

        assertThatThrownBy(() -> service.updateAcc(requester, request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid object class term");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "VALIDATION_ERROR"));
    }

    @Test
    void ownershipFailureHasAStableAccessDeniedCode() {
        ScoreUser developer = new ScoreUser(
                new UserId(BigInteger.valueOf(8)), "developer", "Developer", null, false,
                List.of(ScoreRole.DEVELOPER));
        UserSummaryRecord owner = mock(UserSummaryRecord.class);
        when(owner.userId()).thenReturn(new UserId(BigInteger.valueOf(9)));
        when(before.owner()).thenReturn(owner);
        when(repositories.accQueryRepository(developer)).thenReturn(query);

        assertThatThrownBy(() -> service.updateAcc(developer, request()))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessageContaining("by the owner");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "ACCESS_DENIED"));
    }

    @Test
    void securityLayerDenialHasAStableAccessDeniedCode() {
        when(query.getAccSummary(manifestId)).thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> service.updateAcc(requester, request()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("denied");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "ACCESS_DENIED"));
    }

    @Test
    void publisherFailureCannotChangeASuccessfulUpdate() {
        when(command.update(manifestId, "Order", null, null, null, null, null)).thenReturn(true);
        when(logs.create(any(), any(), anyString())).thenReturn(new LogId(BigInteger.ONE));
        publisher.failImmediate = true;

        assertThat(service.updateAcc(requester, request())).isTrue();

        assertThat(publisher.immediateAttempts).isEqualTo(1);
        verify(command).updateLogId(manifestId, new LogId(BigInteger.ONE));
    }

    @Test
    void activityFailureDoesNotRollBackTheSurroundingTransaction() {
        when(command.update(manifestId, "Order", null, null, null, null, null)).thenReturn(true);
        when(logs.create(any(), any(), anyString())).thenReturn(new LogId(BigInteger.ONE));
        publisher.failImmediate = true;
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        Boolean updated = new TransactionTemplate(transactions)
                .execute(status -> service.updateAcc(requester, request()));

        assertThat(updated).isTrue();
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
    }

    @Test
    void publisherFailureCannotReplaceTheOriginalBusinessFailure() {
        when(before.state()).thenReturn(CcState.Draft);
        publisher.failImmediate = true;

        Throwable thrown = catchThrowable(() -> service.updateAcc(requester, request()));

        assertThat(thrown)
                .isInstanceOf(ScoreActivityException.class)
                .hasMessageContaining("Only ACCs in the 'WIP' state");
        assertThat(publisher.immediateAttempts).isEqualTo(1);
    }

    @Test
    void eventFactoryFailureCannotReplaceTheOriginalBusinessFailure() {
        when(before.state()).thenReturn(CcState.Draft);
        CoreComponentActivityEventFactory failingFactory = mock(CoreComponentActivityEventFactory.class);
        when(failingFactory.updateFailed(anyString(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("factory unavailable"));
        when(applicationContext.getBean(CoreComponentActivityHandler.class))
                .thenReturn(new CoreComponentActivityHandler(
                        failingFactory, new ScoreActivityFailureClassifier()));

        Throwable thrown = catchThrowable(() -> service.updateAcc(requester, request()));

        assertThat(thrown)
                .isInstanceOf(ScoreActivityException.class)
                .hasMessageContaining("Only ACCs in the 'WIP' state");
    }

    @Test
    void disabledPublishingBypassesActivityConstructionAndPublishing() {
        publisher.enabled = false;
        when(command.update(manifestId, "Order", null, null, null, null, null)).thenReturn(false);

        assertThat(service.updateAcc(requester, request())).isFalse();

        verifyNoInteractions(applicationContext);
        assertThat(publisher.immediateAttempts).isZero();
    }

    @Test
    void batchEntryPointPublishesOneEventWithoutDependingOnSelfInvocationAdvice() {
        when(command.update(manifestId, "Order", null, null, null, null, null)).thenReturn(true);
        when(logs.create(any(), any(), anyString())).thenReturn(new LogId(BigInteger.ONE));

        assertThat(service.updateAccList(requester, List.of(request())))
                .containsExactly(manifestId);

        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.targets().getFirst().id()).isEqualTo("42"));
        assertThat(publisher.immediateAttempts).isEqualTo(1);
    }

    @Test
    void partialBatchFailureRollsBackAndPublishesOnlyBatchFailureEvents() {
        AccManifestId secondId = new AccManifestId(BigInteger.valueOf(43));
        AccUpdateRequest secondRequest = new AccUpdateRequest(
                secondId, null, "Shipment", null, null, null, null, null, null);
        when(query.getAccSummary(secondId)).thenReturn(before);
        when(command.update(manifestId, "Order", null, null, null, null, null)).thenReturn(true);
        when(command.update(secondId, "Shipment", null, null, null, null, null))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(logs.create(any(), any(), anyString())).thenReturn(new LogId(BigInteger.ONE));
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .execute(status -> service.updateAccList(requester, List.of(request(), secondRequest))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).hasSize(2).allSatisfy(event ->
                assertThat(event.properties()).containsEntry("errorCode", "BATCH_ROLLED_BACK"));
    }

    @Test
    void duplicateBatchTargetsAreRejectedBeforeMutation() {
        assertThatThrownBy(() -> service.updateAccList(requester, List.of(request(), request())))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessage("Duplicate ACC targets are not allowed in an update batch.");

        verifyNoInteractions(command);
        assertThat(publisher.transactionalEvents).isEmpty();
        assertThat(publisher.immediateEvents).hasSize(2).allSatisfy(event ->
                assertThat(event.properties()).containsEntry("errorCode", "BATCH_ROLLED_BACK"));
    }

    @Test
    void missingAccStateChangeHasATargetNotFoundCode() {
        when(query.getAccSummary(manifestId)).thenReturn(null);

        assertThatThrownBy(() -> service.updateState(requester, manifestId, CcState.Draft))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessage("The ACC does not exist.");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("acc.state-change");
            assertThat(event.targets().getFirst().id()).isEqualTo("42");
            assertThat(event.properties()).containsEntry("errorCode", "TARGET_NOT_FOUND");
        });
    }

    @Test
    void invalidAccStateTransitionHasAnInvalidStateCode() {
        assertThatThrownBy(() -> service.updateState(requester, manifestId, CcState.Production))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessageContaining("cannot move");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "INVALID_STATE"));
    }

    @Test
    void accStateOwnershipFailureHasAnAccessDeniedCode() {
        ScoreUser developer = new ScoreUser(
                new UserId(BigInteger.valueOf(8)), "developer", "Developer", null, false,
                List.of(ScoreRole.DEVELOPER));
        UserSummaryRecord owner = mock(UserSummaryRecord.class);
        when(owner.userId()).thenReturn(new UserId(BigInteger.valueOf(9)));
        when(before.owner()).thenReturn(owner);
        when(repositories.accQueryRepository(developer)).thenReturn(query);

        assertThatThrownBy(() -> service.updateState(developer, manifestId, CcState.Draft))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessageContaining("by the owner");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "ACCESS_DENIED"));
    }

    @Test
    void missingAccDeleteHasATargetNotFoundCode() {
        when(query.getAccSummary(manifestId)).thenReturn(null);

        assertThatThrownBy(() -> service.purge(requester, manifestId))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessage("The ACC does not exist.");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("acc.delete");
            assertThat(event.properties()).containsEntry("errorCode", "TARGET_NOT_FOUND");
        });
    }

    @Test
    void nonDeletedAccPurgeHasAnInvalidStateCode() {
        assertThatThrownBy(() -> service.purge(requester, manifestId))
                .isInstanceOf(ScoreActivityException.class)
                .hasMessageContaining("Only ACCs in the 'Deleted' state");

        assertThat(publisher.immediateEvents).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "INVALID_STATE"));
    }

    private AccUpdateRequest request() {
        return new AccUpdateRequest(
                manifestId, null, "Order", null, null, null, null, null, null);
    }

    private static final class CapturingPublisher implements ScoreActivityEventPublisher {
        private final List<ScoreActivityEvent> transactionalEvents = new ArrayList<>();
        private final List<ScoreActivityEvent> immediateEvents = new ArrayList<>();
        private boolean enabled = true;
        private boolean failTransactional;
        private boolean failImmediate;
        private int transactionalAttempts;
        private int immediateAttempts;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void publish(ScoreActivityEvent event) {
            transactionalAttempts++;
            if (failTransactional) {
                throw new IllegalStateException("publisher unavailable");
            }
            transactionalEvents.add(event);
        }

        @Override
        public void publishImmediately(ScoreActivityEvent event) {
            immediateAttempts++;
            if (failImmediate) {
                throw new IllegalStateException("publisher unavailable");
            }
            immediateEvents.add(event);
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
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

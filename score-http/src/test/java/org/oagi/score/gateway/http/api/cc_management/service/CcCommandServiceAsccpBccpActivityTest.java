package org.oagi.score.gateway.http.api.cc_management.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityException;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityAspect;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventRecorder;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.AsccpCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.asccp.AsccpUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bccp.BccpCreateRequest;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.bccp.BccpUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpDetailsRecord;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpDetailsRecord;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.repository.AccQueryRepository;
import org.oagi.score.gateway.http.api.cc_management.repository.AsccpCommandRepository;
import org.oagi.score.gateway.http.api.cc_management.repository.AsccpQueryRepository;
import org.oagi.score.gateway.http.api.cc_management.repository.BccpCommandRepository;
import org.oagi.score.gateway.http.api.cc_management.repository.BccpQueryRepository;
import org.oagi.score.gateway.http.api.cc_management.repository.DtQueryRepository;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityEventFactory;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityHandler;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityTargetResolver;
import org.oagi.score.gateway.http.api.library_management.model.LibraryId;
import org.oagi.score.gateway.http.api.log_management.model.LogAction;
import org.oagi.score.gateway.http.api.log_management.model.LogId;
import org.oagi.score.gateway.http.api.log_management.repository.LogCommandRepository;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseState;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseSummaryRecord;
import org.oagi.score.gateway.http.api.release_management.repository.ReleaseQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CcCommandServiceAsccpBccpActivityTest {

    private final ScoreUser requester = new ScoreUser(
            new UserId(BigInteger.valueOf(7)), "administrator", "Administrator", null, false,
            List.of(ScoreRole.ADMINISTRATOR));
    private final ReleaseId releaseId = new ReleaseId(BigInteger.valueOf(10));
    private final ReleaseSummaryRecord release = new ReleaseSummaryRecord(
            releaseId, new LibraryId(BigInteger.ONE), "1.0", ReleaseState.Published);
    private final AccManifestId accId = new AccManifestId(BigInteger.valueOf(20));
    private final AsccpManifestId asccpId = new AsccpManifestId(BigInteger.valueOf(21));
    private final DtManifestId dtId = new DtManifestId(BigInteger.valueOf(30));
    private final BccpManifestId bccpId = new BccpManifestId(BigInteger.valueOf(31));

    private final RepositoryFactory repositories = mock(RepositoryFactory.class);
    private final ReleaseQueryRepository releases = mock(ReleaseQueryRepository.class);
    private final AccQueryRepository accQuery = mock(AccQueryRepository.class);
    private final AsccpQueryRepository asccpQuery = mock(AsccpQueryRepository.class);
    private final AsccpCommandRepository asccpCommand = mock(AsccpCommandRepository.class);
    private final DtQueryRepository dtQuery = mock(DtQueryRepository.class);
    private final BccpQueryRepository bccpQuery = mock(BccpQueryRepository.class);
    private final BccpCommandRepository bccpCommand = mock(BccpCommandRepository.class);
    private final LogCommandRepository logs = mock(LogCommandRepository.class);
    private final CapturingPublisher publisher = new CapturingPublisher();
    private final AccSummaryRecord roleAcc = mock(AccSummaryRecord.class);
    private final DtSummaryRecord basedDt = mock(DtSummaryRecord.class);
    private final AsccpSummaryRecord asccp = mock(AsccpSummaryRecord.class);
    private final BccpSummaryRecord bccp = mock(BccpSummaryRecord.class);
    private CcCommandService service;

    @BeforeEach
    void setUp() {
        when(repositories.releaseQueryRepository(requester)).thenReturn(releases);
        when(repositories.accQueryRepository(requester)).thenReturn(accQuery);
        when(repositories.asccpQueryRepository(requester)).thenReturn(asccpQuery);
        when(repositories.asccpCommandRepository(requester)).thenReturn(asccpCommand);
        when(repositories.dtQueryRepository(requester)).thenReturn(dtQuery);
        when(repositories.bccpQueryRepository(requester)).thenReturn(bccpQuery);
        when(repositories.bccpCommandRepository(requester)).thenReturn(bccpCommand);
        when(repositories.logCommandRepository(requester)).thenReturn(logs);
        when(releases.getReleaseSummary(releaseId)).thenReturn(release);
        when(roleAcc.release()).thenReturn(release);
        when(roleAcc.isAbstract()).thenReturn(false);
        when(accQuery.getAccSummary(accId)).thenReturn(roleAcc);
        when(basedDt.release()).thenReturn(release);
        when(dtQuery.getDtSummary(dtId)).thenReturn(basedDt);
        when(asccp.asccpManifestId()).thenReturn(asccpId);
        when(asccp.state()).thenReturn(CcState.WIP);
        when(asccp.den()).thenReturn("Order. Details");
        when(asccp.guid()).thenReturn(new Guid("0123456789abcdef0123456789abcdef"));
        when(asccp.propertyTerm()).thenReturn("Order");
        when(asccpQuery.getAsccpSummary(asccpId)).thenReturn(asccp);
        when(bccp.bccpManifestId()).thenReturn(bccpId);
        when(bccp.state()).thenReturn(CcState.WIP);
        when(bccp.den()).thenReturn("Identifier. Code");
        when(bccp.guid()).thenReturn(new Guid("11111111111111111111111111111111"));
        when(bccp.propertyTerm()).thenReturn("Identifier");
        when(bccpQuery.getBccpSummary(bccpId)).thenReturn(bccp);

        CcCommandService target = new CcCommandService();
        ReflectionTestUtils.setField(target, "repositoryFactory", repositories);
        CoreComponentActivityHandler handler = new CoreComponentActivityHandler(
                new CoreComponentActivityEventFactory(new ScoreActivityEventFactory(
                        Clock.systemUTC(), ScoreActivityContext::empty),
                        new CoreComponentActivityTargetResolver(repositories)),
                new ScoreActivityFailureClassifier());
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(CoreComponentActivityHandler.class)).thenReturn(handler);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new ScoreActivityAspect(
                applicationContext, publisher, new ScoreActivityEventRecorder()));
        service = proxyFactory.getProxy();
    }

    @Test
    void directCreatesPublishTheSameLifecycleShapeForAsccpAndBccp() {
        when(asccpCommand.create(eq(releaseId), eq(accId), any(), any(), any(), any(), any(), any()))
                .thenReturn(asccpId);
        when(bccpCommand.create(releaseId, dtId, "Identifier")).thenReturn(bccpId);
        stubActivityLogs();

        assertThat(service.createAsccp(
                requester, AsccpCreateRequest.builder(releaseId, accId).build()))
                .isEqualTo(asccpId);
        assertThat(service.createBccp(
                requester, BccpCreateRequest.builder(releaseId, dtId)
                        .initialPropertyTerm("Identifier").build()))
                .isEqualTo(bccpId);

        assertThat(publisher.events).extracting(ScoreActivityEvent::name)
                .containsExactly("asccp.create", "bccp.create");
        assertThat(publisher.events).extracting(event -> event.targets().getFirst().type())
                .containsExactly("ASCCP", "BCCP");
    }

    @Test
    void batchUpdatesPublishOneEventPerRequestedComponent() {
        when(asccpCommand.update(eq(asccpId), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(bccpCommand.update(eq(bccpId), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        stubActivityLogs();

        assertThat(service.updateAsccpList(requester, List.of(new AsccpUpdateRequest(
                asccpId, "Order", null, null, null, null, null, null))))
                .containsExactly(asccpId);
        assertThat(service.updateBccpList(requester, List.of(new BccpUpdateRequest(
                bccpId, "Identifier", null, null, null, null, null, null, null))))
                .containsExactly(bccpId);

        assertThat(publisher.events).extracting(ScoreActivityEvent::name)
                .containsExactly("asccp.update", "bccp.update");
        assertThat(publisher.events).allSatisfy(event ->
                assertThat(event.properties()).containsKey("requestedFields"));
    }

    @Test
    void missingStateChangeTargetsPublishTargetNotFoundForBothTypes() {
        when(asccpQuery.getAsccpSummary(asccpId)).thenReturn(null);
        when(bccpQuery.getBccpSummary(bccpId)).thenReturn(null);

        assertThatThrownBy(() -> service.updateState(requester, asccpId, CcState.Deleted))
                .isInstanceOf(ScoreActivityException.class);
        assertThatThrownBy(() -> service.updateState(requester, bccpId, CcState.Deleted))
                .isInstanceOf(ScoreActivityException.class);

        assertThat(publisher.events).extracting(ScoreActivityEvent::name)
                .containsExactly("asccp.state-change", "bccp.state-change");
        assertThat(publisher.events).allSatisfy(event ->
                assertThat(event.properties()).containsEntry("errorCode", "TARGET_NOT_FOUND"));
    }

    @Test
    void updateRequestsWithoutManifestIdsPublishValidationFailures() {
        assertThatThrownBy(() -> service.updateAsccpList(requester, List.of(new AsccpUpdateRequest(
                null, "Order", null, null, null, null, null, null))))
                .isInstanceOf(ScoreActivityException.class);
        assertThatThrownBy(() -> service.updateBccpList(requester, List.of(new BccpUpdateRequest(
                null, "Identifier", null, null, null, null, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(publisher.events).extracting(ScoreActivityEvent::name)
                .containsExactly("asccp.update", "bccp.update");
        assertThat(publisher.events).allSatisfy(event ->
                assertThat(event.properties()).containsEntry("errorCode", "VALIDATION_ERROR"));
        verifyNoInteractions(asccpQuery, bccpQuery);
    }

    @Test
    void purgeRequiresDeletedStateAndPublishesInvalidStateForBothTypes() {
        assertThatThrownBy(() -> service.purge(requester, asccpId))
                .isInstanceOf(ScoreActivityException.class);
        assertThatThrownBy(() -> service.purge(requester, bccpId))
                .isInstanceOf(ScoreActivityException.class);

        assertThat(publisher.events).extracting(ScoreActivityEvent::name)
                .containsExactly("asccp.delete", "bccp.delete");
        assertThat(publisher.events).allSatisfy(event ->
                assertThat(event.properties()).containsEntry("errorCode", "INVALID_STATE"));
    }

    @Test
    void bccpPurgeRejectsADeletedTargetThatStillHasRelatedBccs() {
        when(bccp.state()).thenReturn(CcState.Deleted);
        when(accQuery.getBccSummaryList(bccpId))
                .thenReturn(List.of(mock(BccSummaryRecord.class)));

        assertThatThrownBy(() -> service.purge(requester, bccpId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purge related-BCCs first");

        assertThat(publisher.events).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "VALIDATION_ERROR"));
    }

    private void stubActivityLogs() {
        when(asccpQuery.getAsccpDetails(asccpId)).thenReturn(mock(AsccpDetailsRecord.class));
        when(bccpQuery.getBccpDetails(bccpId)).thenReturn(mock(BccpDetailsRecord.class));
        when(logs.create(any(AsccpDetailsRecord.class), eq(LogAction.Added)))
                .thenReturn(new LogId(BigInteger.ONE));
        when(logs.create(any(BccpDetailsRecord.class), eq(LogAction.Added)))
                .thenReturn(new LogId(BigInteger.TWO));
        when(logs.create(any(AsccpDetailsRecord.class), eq(LogAction.Modified)))
                .thenReturn(new LogId(BigInteger.ONE));
        when(logs.create(any(BccpDetailsRecord.class), eq(LogAction.Modified)))
                .thenReturn(new LogId(BigInteger.TWO));
    }

    private static final class CapturingPublisher implements ScoreActivityEventPublisher {
        private final List<ScoreActivityEvent> events = new ArrayList<>();

        @Override
        public void publish(ScoreActivityEvent event) {
            events.add(event);
        }

        @Override
        public void publishImmediately(ScoreActivityEvent event) {
            events.add(event);
        }
    }
}

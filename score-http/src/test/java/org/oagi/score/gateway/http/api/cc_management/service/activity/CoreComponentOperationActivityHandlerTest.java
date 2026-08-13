package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityExecution;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.cc_management.controller.payload.dt.DtUpdateRequest;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.cc_management.repository.AccQueryRepository;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreComponentOperationActivityHandlerTest {

    private final ScoreUser requester = new ScoreUser(
            new UserId(BigInteger.valueOf(7)), "developer", "Developer", null, false,
            List.of(ScoreRole.DEVELOPER));
    private CoreComponentOperationActivityHandler handler;
    private CoreComponentActivityTargetResolver targets;
    private RepositoryFactory repositories;

    @BeforeEach
    void setUp() {
        targets = mock(CoreComponentActivityTargetResolver.class);
        when(targets.resolveOrReference(anyString(), any(), any())).thenAnswer(invocation ->
                target(invocation.getArgument(0), invocation.getArgument(2)));
        when(targets.resolve(anyString(), any(), any())).thenAnswer(invocation ->
                target(invocation.getArgument(0), invocation.getArgument(2)));
        repositories = mock(RepositoryFactory.class);
        handler = new CoreComponentOperationActivityHandler(
                CoreComponentActivityTestSupport.eventFactory(), targets,
                repositories, new ScoreActivityFailureClassifier());
    }

    @Test
    void batchSucceedsOnlyWhenEveryRequestedTargetWasApplied() {
        DtManifestId first = new DtManifestId(BigInteger.valueOf(41));
        DtManifestId second = new DtManifestId(BigInteger.valueOf(42));
        ScoreActivityExecution execution = handler.start(invocation(List.of(
                DtUpdateRequest.builder(first).build(),
                DtUpdateRequest.builder(second).build())));

        assertThat(execution.isSuccessful(List.of(first))).isFalse();
        assertThat(execution.isSuccessful(List.of(first, second, second))).isFalse();
        assertThat(execution.isSuccessful(List.of(first, "not-a-manifest-id"))).isFalse();
        assertThat(execution.succeeded(List.of(first))).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(ScoreActivityEvent.FAILED);
            assertThat(event.properties()).containsEntry("errorCode", "NOT_APPLIED");
        });

        assertThat(execution.isSuccessful(List.of(first, second))).isTrue();
        assertThat(execution.succeeded(List.of(first, second))).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(ScoreActivityEvent.SUCCEEDED);
            assertThat(event.targets()).hasSize(2);
        });
    }

    @Test
    void discardUsesTheOwnerCapturedBeforeTheAssociationIsDeleted() {
        AsccManifestId asccId = new AsccManifestId(BigInteger.valueOf(51));
        AccManifestId ownerId = new AccManifestId(BigInteger.valueOf(52));
        AsccSummaryRecord association = mock(AsccSummaryRecord.class);
        when(association.fromAccManifestId()).thenReturn(ownerId);
        AccQueryRepository query = mock(AccQueryRepository.class);
        when(query.getAsccSummary(asccId)).thenReturn(association);
        when(repositories.accQueryRepository(requester)).thenReturn(query);

        ScoreActivityExecution execution = handler.start(new ScoreActivityInvocation(
                "acc", "update", "discard-ascc", List.of(requester, asccId)));

        assertThat(execution.succeeded(true)).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(ScoreActivityEvent.SUCCEEDED);
            assertThat(event.targets()).singleElement().satisfies(target ->
                    assertThat(target.id()).isEqualTo(ownerId.toString()));
        });
        verify(targets, never()).resolve("acc", requester, ownerId);
    }

    @Test
    void emptyBatchIsNotAppliedAndMultiItemExceptionIsRolledBack() {
        ScoreActivityExecution empty = handler.start(invocation(List.of()));
        assertThat(empty.isSuccessful(List.of())).isFalse();
        assertThat(empty.succeeded(List.of())).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "NOT_APPLIED"));

        ScoreActivityExecution batch = handler.start(invocation(List.of(
                DtUpdateRequest.builder(new DtManifestId(BigInteger.valueOf(41))).build(),
                DtUpdateRequest.builder(new DtManifestId(BigInteger.valueOf(42))).build())));
        assertThat(batch.failed(new IllegalArgumentException("invalid"))).singleElement().satisfies(event ->
                assertThat(event.properties()).containsEntry("errorCode", "BATCH_ROLLED_BACK"));
    }

    private ScoreActivityInvocation invocation(List<DtUpdateRequest> requests) {
        return new ScoreActivityInvocation(
                "dt", "update", "update-details", List.of(requester, requests));
    }

    private static ScoreActivityTarget target(String category, Object id) {
        return ScoreActivityTarget.primary(category.toUpperCase(), id,
                new Guid("0123456789abcdef0123456789abcdef"), category + " name");
    }
}

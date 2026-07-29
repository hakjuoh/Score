package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMutationApprovalDecisionRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMutationConfirmationDecisionResponse;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationDecision;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingMutationApproval;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_STEP;

class AiMutationApprovalCoordinatorTest {

    private final ScoreUser user = new ScoreUser(new UserId(BigInteger.ONE),
            "tester", "Test User", null, false, List.of());
    private final AiMutationConfirmationService confirmations =
            mock(AiMutationConfirmationService.class);
    private final AiChatConversationRepository conversations =
            mock(AiChatConversationRepository.class);
    private final RepositoryFactory repositories = mock(RepositoryFactory.class);

    @Test
    void batchesParallelChildApprovalsAndResumesEachExactWaiter() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        Map<String, AiMutationApprovalScope> scopes = coordinator.openParallelGroup(
                "root-conversation", "request-1", Map.of(
                        "child-a", new AiMutationApprovalCoordinator.Participant("agent-a", "Agent A"),
                        "child-b", new AiMutationApprovalCoordinator.Participant("agent-b", "Agent B")));
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);

        CompletableFuture<Map<String, AiMutationApprovalResolution>> first =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-conversation-a", scopes.get("child-a"),
                        List.of(pending("approval-a", "update_a")), notices::add));
        assertThat(notices.poll(100, TimeUnit.MILLISECONDS)).isNull();

        CompletableFuture<Map<String, AiMutationApprovalResolution>> second =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-conversation-b", scopes.get("child-b"),
                        List.of(pending("approval-b", "delete_b")), notices::add));

        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        assertThat(batch).isNotNull();
        assertThat(batch.parallel()).isTrue();
        assertThat(batch.rootConversationId()).isEqualTo("root-conversation");
        assertThat(batch.items()).extracting(AiMutationApprovalBatchNotice.Item::confirmationRequestId)
                .containsExactlyInAnyOrder("approval-a", "approval-b");
        assertThat(batch.items()).extracting(AiMutationApprovalBatchNotice.Item::agentLabel)
                .containsExactlyInAnyOrder("Agent A", "Agent B");

        coordinator.decide(user, command(batch, Map.of(
                "approval-a", "APPROVE", "approval-b", "DENY")));

        assertThat(first.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
        assertThat(second.get(1, TimeUnit.SECONDS).get("approval-b").decision())
                .isEqualTo(AiMutationApprovalResolution.Decision.DENY);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiMutationConfirmationService.BatchDecision>> decisions =
                ArgumentCaptor.forClass(List.class);
        verify(confirmations).decideBatch(org.mockito.ArgumentMatchers.eq(user), decisions.capture());
        assertThat(decisions.getValue()).containsExactlyInAnyOrder(
                new AiMutationConfirmationService.BatchDecision(
                        "child-conversation-a", "approval-a", "APPROVE"),
                new AiMutationConfirmationService.BatchDecision(
                        "child-conversation-b", "approval-b", "DENY"));
        verify(conversations, atLeastOnce()).append(any(), any());
        ArgumentCaptor<AiChatTrajectoryStep> recorded =
                ArgumentCaptor.forClass(AiChatTrajectoryStep.class);
        verify(conversations, atLeastOnce()).append(any(), recorded.capture());
        assertThat(recorded.getAllValues())
                .extracting(AiChatTrajectoryStep::messageKind)
                .allSatisfy(messageKind -> assertThat(messageKind.length())
                        .as("persisted message kind length")
                        .isLessThanOrEqualTo(AI_CHAT_STEP.MESSAGE_KIND.getDataType().length()));
        assertThat(recorded.getAllValues())
                .filteredOn(step -> "mutation_approval_batch_requested".equals(step.messageKind()))
                .singleElement()
                .satisfies(step -> assertThat(step.extra())
                        .containsKeys("batchId", "parallel", "expiresAt", "items"));
    }

    @Test
    void publishesCommittedAcknowledgementBeforeReleasingTheExactWaiter() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CountDownLatch waiterReturned = new CountDownLatch(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> waiter =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return coordinator.awaitDecisions(
                                user, "request-1", "root-conversation",
                                AiMutationApprovalScope.root("root-conversation"),
                                List.of(pending("approval-a", "update_a")), notices::add,
                                ignored -> assertThat(waiterReturned.getCount()).isEqualTo(1));
                    } finally {
                        waiterReturned.countDown();
                    }
                });
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);

        coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE")));

        assertThat(waiter.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
    }

    @Test
    void oneAgentPublishesOneBatchContainingEveryPendingToolCall() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        AiMutationApprovalScope scope = AiMutationApprovalScope.individual(
                "root-conversation", "child-a", "agent-a", "Agent A");
        CompletableFuture<Map<String, AiMutationApprovalResolution>> result =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-conversation-a", scope,
                        List.of(pending("approval-a", "update_a"),
                                pending("approval-b", "update_b")), notices::add));

        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        assertThat(batch).isNotNull();
        assertThat(batch.parallel()).isFalse();
        assertThat(batch.items()).hasSize(2);

        coordinator.decide(user, command(batch, Map.of(
                "approval-a", "APPROVE", "approval-b", "APPROVE")));
        assertThat(result.get(1, TimeUnit.SECONDS).values())
                .allMatch(AiMutationApprovalResolution::approved);
    }

    @Test
    void exactRetryOfACommittedDecisionReturnsAnIdempotentAcknowledgement() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> result =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        AiMutationApprovalDecisionRequest command = command(
                batch, Map.of("approval-a", "APPROVE"));

        assertThat(coordinator.decide(user, command)).isNull();
        assertThat(result.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
        assertThat(coordinator.decide(user, command))
                .isEqualTo(new AiMutationApprovalCoordinator.DecisionAcknowledgement(
                        batch.batchId(), 1, 0));
        assertThatThrownBy(() -> coordinator.decide(user,
                command(batch, Map.of("approval-a", "DENY"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different decisions");
        verify(confirmations, times(1)).decideBatch(any(), any());
    }

    @Test
    void exactRetryIsIdempotentWhileTheOriginalCommittedAckIsStillPublishing() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CountDownLatch acknowledgementStarted = new CountDownLatch(1);
        CountDownLatch releaseAcknowledgement = new CountDownLatch(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> waiter =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add,
                        ignored -> {
                            acknowledgementStarted.countDown();
                            try {
                                assertThat(releaseAcknowledgement.await(1, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                        }));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        AiMutationApprovalDecisionRequest command = command(
                batch, Map.of("approval-a", "APPROVE"));
        CompletableFuture<Void> original = CompletableFuture.runAsync(() ->
                coordinator.decide(user, command));

        assertThat(acknowledgementStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.decide(user, command))
                .isEqualTo(new AiMutationApprovalCoordinator.DecisionAcknowledgement(
                        batch.batchId(), 1, 0));
        releaseAcknowledgement.countDown();

        original.get(1, TimeUnit.SECONDS);
        assertThat(waiter.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
        verify(confirmations, times(1)).decideBatch(any(), any());
    }

    @Test
    void waitsForSafeParallelSiblingsBeforePublishingOnlyTheRiskyApprovals() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        Map<String, AiMutationApprovalScope> scopes = coordinator.openParallelGroup(
                "root-conversation", "request-1", Map.of(
                        "risky", new AiMutationApprovalCoordinator.Participant(
                                "risky-agent", "Risky agent"),
                        "safe", new AiMutationApprovalCoordinator.Participant(
                                "safe-agent", "Safe agent")));
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> risky =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "risky-conversation", scopes.get("risky"),
                        List.of(pending("approval-risky", "delete_bbie")), notices::add));

        assertThat(notices.poll(100, TimeUnit.MILLISECONDS)).isNull();
        coordinator.participantFinished(scopes.get("safe"));

        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        assertThat(batch).isNotNull();
        assertThat(batch.items()).singleElement().satisfies(item -> {
            assertThat(item.confirmationRequestId()).isEqualTo("approval-risky");
            assertThat(item.agentLabel()).isEqualTo("Risky agent");
        });
        coordinator.decide(user, command(batch, Map.of("approval-risky", "APPROVE")));
        assertThat(risky.get(1, TimeUnit.SECONDS).get("approval-risky").approved()).isTrue();
    }

    @Test
    void reservesAChildConfirmationAgainstLegacyDecisionsUntilItsWaitEnds() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        Map<String, AiMutationApprovalScope> scopes = coordinator.openParallelGroup(
                "root-conversation", "request-1", Map.of(
                        "risky", new AiMutationApprovalCoordinator.Participant(
                                "risky-agent", "Risky agent"),
                        "safe", new AiMutationApprovalCoordinator.Participant(
                                "safe-agent", "Safe agent")));
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> risky =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-conversation", scopes.get("risky"),
                        List.of(pending("approval-risky", "delete_bbie")), notices::add));
        coordinator.participantFinished(scopes.get("safe"));

        assertThat(notices.poll(1, TimeUnit.SECONDS)).isNotNull();
        assertThatThrownBy(() -> coordinator.whileConfirmationUnreserved(
                "approval-risky", () -> "legacy-decision"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active approval request");

        coordinator.cancelRequest("request-1");
        assertThat(risky.get(1, TimeUnit.SECONDS).get("approval-risky").approved()).isFalse();
        assertThat(coordinator.whileConfirmationUnreserved(
                "approval-risky", () -> "legacy-decision")).isEqualTo("legacy-decision");
    }

    @Test
    void rejectsPartialBatchDecisionsWithoutReleasingAnyChild() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> result =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a"),
                                pending("approval-b", "update_b")), notices::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);

        assertThatThrownBy(() -> coordinator.decide(user,
                command(batch, Map.of("approval-a", "APPROVE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Every mutation approval item");
        assertThat(result).isNotDone();

        coordinator.cancelRequest("request-1");
        assertThat(result.get(1, TimeUnit.SECONDS).values())
                .allMatch(resolution -> resolution.decision()
                        == AiMutationApprovalResolution.Decision.DENY);
    }

    @Test
    void givesSequentialAgentsSeparateApprovalBatches() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(2);

        CompletableFuture<Map<String, AiMutationApprovalResolution>> first =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-a",
                        AiMutationApprovalScope.individual(
                                "root-conversation", "child-a", "agent-a", "Agent A"),
                        List.of(pending("approval-a", "update_a")), notices::add));
        AiMutationApprovalBatchNotice firstBatch = notices.poll(1, TimeUnit.SECONDS);
        coordinator.decide(user, command(firstBatch, Map.of("approval-a", "APPROVE")));
        assertThat(first.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();

        CompletableFuture<Map<String, AiMutationApprovalResolution>> second =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-b",
                        AiMutationApprovalScope.individual(
                                "root-conversation", "child-b", "agent-b", "Agent B"),
                        List.of(pending("approval-b", "delete_b")), notices::add));
        AiMutationApprovalBatchNotice secondBatch = notices.poll(1, TimeUnit.SECONDS);

        assertThat(secondBatch.batchId()).isNotEqualTo(firstBatch.batchId());
        assertThat(secondBatch.items()).singleElement()
                .extracting(AiMutationApprovalBatchNotice.Item::agentLabel)
                .isEqualTo("Agent B");
        coordinator.decide(user, command(secondBatch, Map.of("approval-b", "DENY")));
        assertThat(second.get(1, TimeUnit.SECONDS).get("approval-b").decision())
                .isEqualTo(AiMutationApprovalResolution.Decision.DENY);
    }

    @Test
    void resumesWaitingAgentOnlyAfterTheApprovalTransactionCommits() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> result =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);

        TransactionSynchronizationManager.initSynchronization();
        try {
            coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE")));
            assertThat(result).isNotDone();

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(result.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
    }

    @Test
    void clearsTheFirstParallelWaveBeforeFastChildrenEnterTheNextWave() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        Map<String, AiMutationApprovalScope> scopes = coordinator.openParallelGroup(
                "root-conversation", "request-1", Map.of(
                        "child-a", new AiMutationApprovalCoordinator.Participant("agent-a", "Agent A"),
                        "child-b", new AiMutationApprovalCoordinator.Participant("agent-b", "Agent B")));
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(2);

        CompletableFuture<Map<String, AiMutationApprovalResolution>> first =
                twoApprovalWaves(coordinator, scopes.get("child-a"), "child-a", "a", notices);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> second =
                twoApprovalWaves(coordinator, scopes.get("child-b"), "child-b", "b", notices);
        AiMutationApprovalBatchNotice firstBatch = notices.poll(1, TimeUnit.SECONDS);
        coordinator.decide(user, command(firstBatch, Map.of(
                "approval-a-1", "APPROVE", "approval-b-1", "APPROVE")));

        AiMutationApprovalBatchNotice secondBatch = notices.poll(1, TimeUnit.SECONDS);
        assertThat(secondBatch.items()).extracting(
                        AiMutationApprovalBatchNotice.Item::confirmationRequestId)
                .containsExactlyInAnyOrder("approval-a-2", "approval-b-2");
        coordinator.decide(user, command(secondBatch, Map.of(
                "approval-a-2", "DENY", "approval-b-2", "DENY")));

        assertThat(first.get(1, TimeUnit.SECONDS).get("approval-a-2").decision())
                .isEqualTo(AiMutationApprovalResolution.Decision.DENY);
        assertThat(second.get(1, TimeUnit.SECONDS).get("approval-b-2").decision())
                .isEqualTo(AiMutationApprovalResolution.Decision.DENY);
    }

    @Test
    void rejectsAnotherOwnerAndMismatchedRootIdentityWithoutReleasingTheWaiter() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> result =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        ScoreUser anotherUser = new ScoreUser(new UserId(BigInteger.TWO),
                "other", "Other User", null, false, List.of());

        assertThatThrownBy(() -> coordinator.decide(anotherUser,
                command(batch, Map.of("approval-a", "APPROVE"))))
                .isInstanceOf(AccessDeniedException.class);
        AiMutationApprovalDecisionRequest wrongRoot = new AiMutationApprovalDecisionRequest(
                batch.requestId(), "different-root", batch.batchId(), List.of(
                new AiMutationApprovalDecisionRequest.ItemDecision("approval-a", "APPROVE")));
        assertThatThrownBy(() -> coordinator.decide(user, wrongRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity does not match");
        assertThat(result).isNotDone();
        coordinator.cancelRequest("request-1");
        assertThat(result.get(1, TimeUnit.SECONDS).get("approval-a").decision())
                .isEqualTo(AiMutationApprovalResolution.Decision.DENY);
    }

    @Test
    void rollbackKeepsTheBatchRetryableAndDoesNotResumeTheWaiter() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator();
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> result =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);

        TransactionSynchronizationManager.initSynchronization();
        try {
            coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE")));
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(result).isNotDone();

        coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE")));
        assertThat(result.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
    }

    @Test
    void timeoutDeniesAndRemovesAParallelWaiter() throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator(Duration.ofMillis(75));
        Map<String, AiMutationApprovalScope> scopes = coordinator.openParallelGroup(
                "root-conversation", "request-1", Map.of(
                        "risky", new AiMutationApprovalCoordinator.Participant("agent-a", "Agent A"),
                        "safe", new AiMutationApprovalCoordinator.Participant("agent-b", "Agent B")));
        Map<String, AiMutationApprovalResolution> result = coordinator.awaitDecisions(
                user, "request-1", "child-a", scopes.get("risky"),
                List.of(pending("approval-a", "update_a")), ignored -> {});

        assertThat(result.get("approval-a").decision())
                .isEqualTo(AiMutationApprovalResolution.Decision.DENY);
        coordinator.participantFinished(scopes.get("safe"));
        coordinator.participantFinished(scopes.get("risky"));
    }

    @Test
    void confirmationExpiryCancelsTheBatchBeforeTheRequestTimeoutAndAllowsAnotherWave()
            throws Exception {
        AiMutationApprovalCoordinator coordinator = coordinator(Duration.ofSeconds(2));
        Map<String, AiMutationApprovalScope> scopes = coordinator.openParallelGroup(
                "root-conversation", "request-1", Map.of(
                        "child-a", new AiMutationApprovalCoordinator.Participant("agent-a", "Agent A"),
                        "child-b", new AiMutationApprovalCoordinator.Participant("agent-b", "Agent B")));
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(2);
        Instant confirmationExpiry = Instant.now().plusMillis(200);
        long startedAt = System.nanoTime();
        CompletableFuture<Map<String, AiMutationApprovalResolution>> first =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-a", scopes.get("child-a"),
                        List.of(pending("approval-a-1", "update_a", confirmationExpiry)),
                        notices::add));
        CompletableFuture<Map<String, AiMutationApprovalResolution>> second =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-b", scopes.get("child-b"),
                        List.of(pending("approval-b-1", "update_b", confirmationExpiry)),
                        notices::add));

        AiMutationApprovalBatchNotice expiredBatch = notices.poll(1, TimeUnit.SECONDS);
        assertThat(expiredBatch).isNotNull();
        assertThat(expiredBatch.expiresAt()).isEqualTo(confirmationExpiry);
        assertThat(first.get(1, TimeUnit.SECONDS).values())
                .allMatch(resolution -> !resolution.approved());
        assertThat(second.get(1, TimeUnit.SECONDS).values())
                .allMatch(resolution -> !resolution.approved());
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofSeconds(1));
        assertThatThrownBy(() -> coordinator.decide(user, command(expiredBatch, Map.of(
                "approval-a-1", "APPROVE", "approval-b-1", "APPROVE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer pending");

        CompletableFuture<Map<String, AiMutationApprovalResolution>> nextFirst =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-a", scopes.get("child-a"),
                        List.of(pending("approval-a-2", "update_a")), notices::add));
        CompletableFuture<Map<String, AiMutationApprovalResolution>> nextSecond =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "child-b", scopes.get("child-b"),
                        List.of(pending("approval-b-2", "update_b")), notices::add));
        AiMutationApprovalBatchNotice nextBatch = notices.poll(1, TimeUnit.SECONDS);
        assertThat(nextBatch).isNotNull();
        assertThat(nextBatch.batchId()).isNotEqualTo(expiredBatch.batchId());
        coordinator.decide(user, command(nextBatch, Map.of(
                "approval-a-2", "APPROVE", "approval-b-2", "APPROVE")));
        assertThat(nextFirst.get(1, TimeUnit.SECONDS).values())
                .allMatch(AiMutationApprovalResolution::approved);
        assertThat(nextSecond.get(1, TimeUnit.SECONDS).values())
                .allMatch(AiMutationApprovalResolution::approved);
    }

    @Test
    void decisionStartedBeforeExpiryWinsWithoutReleasingADenial() throws Exception {
        CountDownLatch decisionStarted = new CountDownLatch(1);
        CountDownLatch finishDecision = new CountDownLatch(1);
        AiMutationApprovalCoordinator coordinator = coordinator(Duration.ofSeconds(2));
        doAnswer(invocation -> {
            decisionStarted.countDown();
            assertThat(finishDecision.await(1, TimeUnit.SECONDS)).isTrue();
            return decisions(invocation.getArgument(1));
        }).when(confirmations).decideBatch(any(), any());
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        Instant expiresAt = Instant.now().plusMillis(250);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> waiter =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a", expiresAt)), notices::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        CompletableFuture<Void> decision = CompletableFuture.runAsync(() ->
                coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE"))));

        assertThat(decisionStarted.await(1, TimeUnit.SECONDS)).isTrue();
        long untilAfterExpiry = Math.max(1L,
                Duration.between(Instant.now(), expiresAt.plusMillis(75)).toMillis());
        Thread.sleep(untilAfterExpiry);
        assertThat(waiter).isNotDone();

        finishDecision.countDown();
        decision.get(1, TimeUnit.SECONDS);
        assertThat(waiter.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
    }

    @Test
    void approvalWaitOutlivesRequestInactivityAndUsesItsOwnTimeout() throws Exception {
        AiRequestRegistry requestRegistry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = requestRegistry.register(
                "request-long-approval", "root-conversation", user,
                Instant.now().plusMillis(80));
        when(repositories.aiChatConversationRepository(any(), any())).thenReturn(conversations);
        when(confirmations.decideBatch(any(), any())).thenAnswer(invocation ->
                decisions(invocation.getArgument(1)));
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setMutationApprovalTimeout(Duration.ofSeconds(2));
        AiMutationApprovalCoordinator coordinator = new AiMutationApprovalCoordinator(
                confirmations, repositories, properties, requestRegistry, ignored -> { });
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> waiter =
                CompletableFuture.supplyAsync(() -> {
                    assertThat(requestRegistry.start(entry)).isTrue();
                    Map<String, AiMutationApprovalResolution> result = coordinator.awaitDecisions(
                            user, entry.requestId(), "root-conversation",
                            AiMutationApprovalScope.root("root-conversation"),
                            List.of(pending("approval-a", "update_a")), notices::add);
                    requestRegistry.finish(entry, null);
                    return result;
                });

        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        Thread.sleep(240);

        assertThat(waiter).isNotDone();
        assertThat(requestRegistry.status(entry.requestId(), user).status()).isEqualTo("RUNNING");

        coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE")));
        assertThat(waiter.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
        assertThat(requestRegistry.status(entry.requestId(), user).status())
                .isEqualTo("COMPLETED");
    }

    @Test
    void decisionCrossingTheApprovalDeadlineRollsBackWithoutAcknowledging()
            throws Exception {
        CountDownLatch decisionStarted = new CountDownLatch(1);
        CountDownLatch finishDecision = new CountDownLatch(1);
        AiRequestRegistry requestRegistry = new AiRequestRegistry();
        requestRegistry.register("request-1", "root-conversation", user,
                Instant.now().plusSeconds(60));
        when(repositories.aiChatConversationRepository(any(), any())).thenReturn(conversations);
        doAnswer(invocation -> {
            decisionStarted.countDown();
            assertThat(finishDecision.await(1, TimeUnit.SECONDS)).isTrue();
            return decisions(invocation.getArgument(1));
        }).when(confirmations).decideBatch(any(), any());
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setMutationApprovalTimeout(Duration.ofMillis(350));
        AiMutationApprovalCoordinator coordinator = new AiMutationApprovalCoordinator(
                confirmations, repositories, properties, requestRegistry, ignored -> { });
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        ArrayBlockingQueue<AiMutationApprovalCoordinator.DecisionAcknowledgement> acknowledgements =
                new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> waiter =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add,
                        acknowledgements::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        CompletableFuture<Void> decision = CompletableFuture.runAsync(() ->
                coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE"))));

        assertThat(decisionStarted.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(Math.max(1L,
                Duration.between(Instant.now(), batch.expiresAt().plusMillis(50)).toMillis()));
        finishDecision.countDown();

        assertThatThrownBy(() -> decision.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(waiter.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isFalse();
        assertThat(acknowledgements).isEmpty();
    }

    @Test
    void decisionCrossingTheDeadlineWhileRecordingIsRejectedBeforeCommit() throws Exception {
        AiRequestRegistry requestRegistry = new AiRequestRegistry();
        Instant deadline = Instant.now().plusMillis(750);
        requestRegistry.register("request-1", "root-conversation", user,
                Instant.now().plusSeconds(60));
        when(repositories.aiChatConversationRepository(any(), any())).thenReturn(conversations);
        when(confirmations.decideBatch(any(), any())).thenAnswer(invocation ->
                decisions(invocation.getArgument(1)));
        doAnswer(invocation -> {
            AiChatTrajectoryStep step = invocation.getArgument(1);
            if ("mutation_approval_decision".equals(step.messageKind())) {
                Thread.sleep(Math.max(1L,
                        Duration.between(Instant.now(), deadline.plusMillis(50)).toMillis()));
            }
            return null;
        }).when(conversations).append(any(), any());
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setMutationApprovalTimeout(Duration.ofMillis(750));
        AiMutationApprovalCoordinator coordinator = new AiMutationApprovalCoordinator(
                confirmations, repositories, properties, requestRegistry, ignored -> { });
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        ArrayBlockingQueue<AiMutationApprovalCoordinator.DecisionAcknowledgement> acknowledgements =
                new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> waiter =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add,
                        acknowledgements::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);

        TransactionSynchronizationManager.initSynchronization();
        try {
            coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE")));
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThatThrownBy(() -> synchronizations.forEach(
                    synchronization -> synchronization.beforeCommit(false)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("approval deadline");
            synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(waiter.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isFalse();
        assertThat(acknowledgements).isEmpty();
    }

    @Test
    void decisionStartedBeforeCancellationKeepsOneAuthoritativeOutcome() throws Exception {
        CountDownLatch decisionStarted = new CountDownLatch(1);
        CountDownLatch finishDecision = new CountDownLatch(1);
        AiMutationApprovalCoordinator coordinator = coordinator(Duration.ofSeconds(2));
        doAnswer(invocation -> {
            decisionStarted.countDown();
            assertThat(finishDecision.await(1, TimeUnit.SECONDS)).isTrue();
            return decisions(invocation.getArgument(1));
        }).when(confirmations).decideBatch(any(), any());
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> waiter =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add));
        AiMutationApprovalBatchNotice batch = notices.poll(1, TimeUnit.SECONDS);
        CompletableFuture<Void> decision = CompletableFuture.runAsync(() ->
                coordinator.decide(user, command(batch, Map.of("approval-a", "APPROVE"))));

        assertThat(decisionStarted.await(1, TimeUnit.SECONDS)).isTrue();
        coordinator.cancelRequest("request-1");
        assertThat(waiter).isNotDone();

        finishDecision.countDown();
        decision.get(1, TimeUnit.SECONDS);
        assertThat(waiter.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isTrue();
    }

    @Test
    void cancellationBetweenRegistrationAndPublicationCannotEmitAStaleNotice() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        AiMutationApprovalCoordinator coordinator = coordinator(Duration.ofSeconds(2), ignored -> {
            registered.countDown();
            try {
                assertThat(allowPublication.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
        ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<Map<String, AiMutationApprovalResolution>> waiter =
                CompletableFuture.supplyAsync(() -> coordinator.awaitDecisions(
                        user, "request-1", "root-conversation",
                        AiMutationApprovalScope.root("root-conversation"),
                        List.of(pending("approval-a", "update_a")), notices::add));

        assertThat(registered.await(1, TimeUnit.SECONDS)).isTrue();
        coordinator.cancelRequest("request-1");
        allowPublication.countDown();

        assertThat(waiter.get(1, TimeUnit.SECONDS).get("approval-a").approved()).isFalse();
        assertThat(notices.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    private AiMutationApprovalCoordinator coordinator() {
        return coordinator(Duration.ofSeconds(2));
    }

    private AiMutationApprovalCoordinator coordinator(Duration timeout) {
        return coordinator(timeout, ignored -> { });
    }

    private AiMutationApprovalCoordinator coordinator(
            Duration timeout, Consumer<String> batchRegisteredHook) {
        when(repositories.aiChatConversationRepository(any(), any())).thenReturn(conversations);
        when(confirmations.decideBatch(any(), any())).thenAnswer(invocation ->
                decisions(invocation.getArgument(1)));
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setMutationApprovalTimeout(timeout);
        return new AiMutationApprovalCoordinator(
                confirmations, repositories, properties, batchRegisteredHook);
    }

    private List<AiMutationDecision> decisions(
            List<AiMutationConfirmationService.BatchDecision> items) {
        return items.stream().map(item -> {
            String grant = "APPROVE".equals(item.decision())
                    ? "grant-" + item.confirmationRequestId() : null;
            return new AiMutationDecision(new AiMutationConfirmationDecisionResponse(
                    item.confirmationRequestId(), null,
                    "APPROVE".equals(item.decision()) ? "APPROVED" : "DENIED",
                    item.decision(), Instant.now().plusSeconds(60),
                    null, null, null, null, grant), HttpStatus.OK);
        }).toList();
    }

    private CompletableFuture<Map<String, AiMutationApprovalResolution>> twoApprovalWaves(
            AiMutationApprovalCoordinator coordinator, AiMutationApprovalScope scope,
            String conversationId, String suffix,
            ArrayBlockingQueue<AiMutationApprovalBatchNotice> notices) {
        return CompletableFuture.supplyAsync(() -> {
            coordinator.awaitDecisions(user, "request-1", conversationId, scope,
                    List.of(pending("approval-" + suffix + "-1", "update_" + suffix)), notices::add);
            return coordinator.awaitDecisions(user, "request-1", conversationId, scope,
                    List.of(pending("approval-" + suffix + "-2", "update_" + suffix)), notices::add);
        });
    }

    private AiPendingMutationApproval pending(String id, String toolName) {
        return pending(id, toolName, Instant.now().plusSeconds(60));
    }

    private AiPendingMutationApproval pending(String id, String toolName, Instant expiresAt) {
        return new AiPendingMutationApproval(new AiMutationConfirmationNotice(
                id, "REQUESTED", expiresAt, toolName, "{\"id\":1}"),
                toolName, "{\"id\":1}");
    }

    private AiMutationApprovalDecisionRequest command(
            AiMutationApprovalBatchNotice batch, Map<String, String> decisions) {
        return new AiMutationApprovalDecisionRequest(batch.requestId(),
                batch.rootConversationId(), batch.batchId(), decisions.entrySet().stream()
                .map(entry -> new AiMutationApprovalDecisionRequest.ItemDecision(
                        entry.getKey(), entry.getValue())).toList());
    }
}

package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.api.ai_management.trajectory.TrajectoryStepAppender;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persists approval requests and decisions as guardrail-evaluation trajectory steps. */
final class AiApprovalTrajectoryWriter {

    private final RepositoryFactory repositoryFactory;
    private final TrajectoryStepAppender trajectorySteps;

    AiApprovalTrajectoryWriter(RepositoryFactory repositoryFactory, ExecutionObserver observer) {
        this.repositoryFactory = Objects.requireNonNull(repositoryFactory, "repositoryFactory");
        this.trajectorySteps = new TrajectoryStepAppender(
                observer != null ? observer : ExecutionObserver.noop());
    }

    void requested(ScoreUser requester, AiChangeApprovalBatchNotice notice, long generation) {
        List<Map<String, Object>> items = notice.items().stream().map(item -> Map.<String, Object>of(
                "confirmationRequestId", item.confirmationRequestId(),
                "toolName", item.toolName(),
                "argumentsSummary", item.argumentsSummary(),
                "agentId", Objects.toString(item.agentId(), ""),
                "agentLabel", Objects.toString(item.agentLabel(), ""))).toList();
        append(requester, notice.rootConversationId(), new AiChatTrajectoryStep(
                notice.requestId(), "system", "change_approval_batch_requested", "visible",
                notice.items().size() == 1
                        ? "Approval requested for one change."
                        : "Approval requested for " + notice.items().size() + " changes.",
                null, null, null, null, null, null,
                Map.of("batchId", notice.batchId(), "parallel", notice.parallel(),
                        "expiresAt", notice.expiresAt().toString(), "items", items),
                0, null, Instant.now()), generation);
    }

    void decided(ScoreUser requester, String rootConversationId, String requestId,
                 String batchId, long generation, Map<String, String> decisions) {
        long approved = decisions.values().stream().filter("APPROVE"::equals).count();
        long denied = decisions.size() - approved;
        append(requester, rootConversationId, new AiChatTrajectoryStep(
                requestId, "user", "change_approval_decision", "visible",
                "Approved " + approved + " change" + (approved == 1 ? "" : "s")
                        + " and denied " + denied + ".",
                null, null, null, null, null, null,
                Map.of("batchId", batchId, "decisions", decisions),
                0, null, Instant.now()), generation);
    }

    private void append(ScoreUser requester, String conversationId,
                        AiChatTrajectoryStep step, long generation) {
        AiChatConversationRepository repository = repositoryFactory.aiChatConversationRepository(
                requester, AiChatJsonSerializer.getInstance());
        trajectorySteps.append(new TrajectoryStepAppender.Command(repository, requester,
                conversationId, step, ExecutionScope.Purpose.GUARDRAIL_EVALUATION,
                Map.of(), () -> { }, generation));
    }
}

package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventPublisher;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationSettings;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiPersistentWorkflowCommand;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.trajectory.TrajectoryStepAppender;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Owns durable conversation lifecycle facts and their transaction-aware observations. */
final class ChatConversationJournal {

    private final Function<ScoreUser, AiChatConversationRepository> repositories;
    private final ScoreAiObservability observability;
    private final TrajectoryStepAppender trajectorySteps;

    ChatConversationJournal(Function<ScoreUser, AiChatConversationRepository> repositories,
                            ScoreAiObservability observability,
                            TrajectoryStepAppender trajectorySteps) {
        this.repositories = repositories;
        this.observability = observability;
        this.trajectorySteps = trajectorySteps;
    }

    void recordFailure(ChatRequest request, ScoreUser requester, String message,
                       String failureClass, long generation) {
        if (request == null || !StringUtils.hasText(request.conversationId())) return;
        Map<String, Object> extra = new LinkedHashMap<>(
                observability.correlation(request.requestId()));
        extra.put("terminal", true);
        if (StringUtils.hasText(failureClass)) extra.put("failure_class", failureClass);
        append(requester, repository(requester), request.conversationId(),
                new AiChatTrajectoryStep(request.requestId(), "system", "error", "visible",
                        StringUtils.hasText(message) ? message
                                : "The assistant request failed.",
                        null, null, null, null, null, Map.copyOf(extra), 0, null, null),
                ExecutionScope.Purpose.USER_RESPONSE,
                Map.of("failure_type", Objects.requireNonNullElse(failureClass, "unknown")),
                generation);
    }

    void recordCompaction(ScoreUser requester, ChatRequest request,
                          long beforeTokens, long afterTokens, String summary,
                          boolean automatic, long generation) {
        append(requester, repository(requester), request.conversationId(),
                new AiChatTrajectoryStep(request.requestId(), "system", "context_compaction",
                        "debug", automatic
                        ? "Conversation context compacted automatically."
                        : "Conversation context compacted.", null, request.modelName(),
                        request.reasoningEffort(), null, null,
                        Map.of("context_input_tokens", Math.max(0L, afterTokens),
                                "context_estimated", true),
                        withTrace(observability.correlation(request.requestId()), Map.of(
                                "automatic", automatic,
                                "before_input_tokens", Math.max(0L, beforeTokens),
                                "after_input_tokens", Math.max(0L, afterTokens),
                                "summary_characters",
                                Objects.requireNonNullElse(summary, "").length())),
                        0, null, null), ExecutionScope.Purpose.COMPACTION,
                Map.of("automatic", automatic), generation);
    }

    void recordSettingsChange(ScoreUser requester, String conversationId, String requestId,
                              AiChatConversationSettings previous,
                              AiChatConversationSettings updated, long generation) {
        if (previous != null && sameSettings(previous, updated)) return;
        Map<String, Object> extra = new LinkedHashMap<>(observability.correlation(requestId));
        if (previous != null) extra.put("before", settingsSnapshot(previous));
        extra.put("after", settingsSnapshot(updated));
        append(requester, repository(requester), conversationId,
                new AiChatTrajectoryStep(requestId, "system", "settings_change", "debug",
                        previous == null ? "Assistant settings initialized."
                                : "Assistant settings changed.",
                        null, updated.modelName(), updated.reasoningEffort(), null, null, null,
                        Map.copyOf(extra), 0, null, null),
                ExecutionScope.Purpose.USER_RESPONSE, Map.of(), generation);
    }

    void recordWorkflowPreference(ScoreUser requester, String conversationId, String requestId,
                                  AiPersistentWorkflowCommand command, String modelName,
                                  String reasoningEffort, long generation) {
        Map<String, Object> extra = new LinkedHashMap<>(observability.correlation(requestId));
        if (command.activeWorkflow() != null) {
            extra.put("activeWorkflow", command.activeWorkflow());
        }
        extra.put("automatic", command.activeWorkflow() == null);
        append(requester, repository(requester), conversationId,
                new AiChatTrajectoryStep(requestId, "system", "workflow_preference", "debug",
                        command.activeWorkflow() == null
                                ? "Automatic workflow selection enabled."
                                : "Active workflow set to " + command.activeWorkflow() + ".",
                        null, modelName, reasoningEffort, null, null, null,
                        Map.copyOf(extra), 0, null, null),
                ExecutionScope.Purpose.WORKFLOW_PLANNING,
                Map.of("workflow", Objects.requireNonNullElse(
                        command.activeWorkflow(), "automatic")), generation);
    }

    void append(ScoreUser requester, AiChatConversationRepository repository,
                String conversationId, AiChatTrajectoryStep step,
                ExecutionScope.Purpose purpose, Map<String, Object> attributes,
                long generation) {
        append(requester, repository, conversationId, step, purpose, attributes,
                () -> { }, generation);
    }

    void append(ScoreUser requester, AiChatConversationRepository repository,
                String conversationId, AiChatTrajectoryStep step,
                ExecutionScope.Purpose purpose, Map<String, Object> attributes,
                Runnable afterAppend, long generation) {
        trajectorySteps.append(new TrajectoryStepAppender.Command(repository, requester,
                conversationId, step, purpose, attributes, afterAppend, generation));
    }

    void completeAfterTransaction(ScoreAiObservability.Turn turn,
                                  String outcome, Throwable failure) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            turn.complete(outcome, failure);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        ExecutionEventPublisher.runAfterTransactionCompletion(() -> {
                            if (status == STATUS_COMMITTED) {
                                turn.complete(outcome, failure);
                                return;
                            }
                            Throwable rollback = failure != null ? failure
                                    : new IllegalStateException(
                                    "AI conversation transaction rolled back");
                            turn.complete(failure != null ? outcome : "FAILED", rollback);
                        });
                    }
                });
    }

    AiChatConversationRepository repository(ScoreUser requester) {
        return repositories.apply(requester);
    }

    private Map<String, Object> withTrace(Map<String, Object> traceContext,
                                          Map<String, Object> metadata) {
        Map<String, Object> merged = new LinkedHashMap<>(
                traceContext != null ? traceContext : Map.of());
        if (metadata != null) merged.putAll(metadata);
        return Map.copyOf(merged);
    }

    private Map<String, Object> settingsSnapshot(AiChatConversationSettings settings) {
        return Map.of("modelName", settings.modelName(),
                "reasoningEffort", settings.reasoningEffort());
    }

    private boolean sameSettings(AiChatConversationSettings left,
                                 AiChatConversationSettings right) {
        return Objects.equals(left.modelName(), right.modelName())
                && Objects.equals(left.reasoningEffort(), right.reasoningEffort());
    }
}

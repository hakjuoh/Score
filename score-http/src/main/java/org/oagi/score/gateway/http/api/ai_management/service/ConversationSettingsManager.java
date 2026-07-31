package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiConversationModelResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AiSensitiveDataRedactor;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationSettings;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatLatestUsage;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiPersistentWorkflowCommand;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowIntent;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Owns conversation settings admission and model-switch compaction. */
final class ConversationSettingsManager {

    private final ScoreAiModelRegistry models;
    private final ChatPromptAssembler prompts;
    private final AiConversationUseCases conversations;
    private final AiContextBudgetService contextBudgets;
    private final ConversationCompactionSupport compactions;
    private final ChatConversationJournal journal;
    private final ObjectMapper objectMapper;
    private final ScoreAiObservability observability;
    private final ExecutionObserver observer;

    ConversationSettingsManager(ScoreAiModelRegistry models, ChatPromptAssembler prompts,
                                AiConversationUseCases conversations,
                                AiContextBudgetService contextBudgets,
                                ConversationCompactionSupport compactions,
                                ChatConversationJournal journal,
                                ObjectMapper objectMapper,
                                ScoreAiObservability observability,
                                ExecutionObserver observer) {
        this.models = models;
        this.prompts = prompts;
        this.conversations = conversations;
        this.contextBudgets = contextBudgets;
        this.compactions = compactions;
        this.journal = journal;
        this.objectMapper = objectMapper;
        this.observability = observability;
        this.observer = observer;
    }

    ChatRequest prepare(ChatRequest request, ScoreUser requester, long generation) {
        prompts.validate(request);
        AiChatConversationRepository repository = conversations.repository(requester);
        Optional<AiPersistentWorkflowCommand> workflowCommand =
                AiWorkflowIntent.persistentWorkflowCommand(request.prompt());
        String requestedModelName = request.modelName();
        String requestedReasoningEffort = request.reasoningEffort();
        AiChatConversationSettings previous = null;
        String storedActiveWorkflow = null;
        if (StringUtils.hasText(request.conversationId())) {
            previous = repository.settingsForUpdate(request.conversationId());
            Optional<String> stored = repository.activeWorkflow(request.conversationId());
            storedActiveWorkflow = AiConversationUseCases.normalizeWorkflowPreference(
                    stored != null ? stored.orElse(null) : null);
            if (!StringUtils.hasText(requestedModelName)) {
                requestedModelName = previous.modelName();
            }
            if (!StringUtils.hasText(requestedReasoningEffort)) {
                requestedReasoningEffort = previous.reasoningEffort();
            }
        }
        String activeWorkflow = workflowCommand.isPresent()
                ? workflowCommand.orElseThrow().activeWorkflow() : storedActiveWorkflow;
        request = AiWorkflowIntent.applyExplicitDelegation(
                request.withActiveWorkflow(activeWorkflow));
        if (request.changeConfirmation() != null) {
            request = request.withActiveWorkflow("assistant")
                    .withMultiAgent(AiMultiAgentOptions.single());
        }
        String modelName = models.resolveModelName(requestedModelName);
        if (previous != null && !modelName.equals(previous.modelName())) {
            throw new IllegalArgumentException(
                    "Change the conversation model before sending the next request.");
        }
        String reasoningEffort = models.resolveReasoningEffort(
                modelName, requestedReasoningEffort);
        String conversationId = repository.open(request.conversationId(),
                AiSensitiveDataRedactor.redactText(request.prompt()));
        journal.recordSettingsChange(requester, conversationId, request.requestId(), previous,
                new AiChatConversationSettings(modelName, reasoningEffort), generation);
        if (workflowCommand.isPresent()) {
            journal.recordWorkflowPreference(requester, conversationId, request.requestId(),
                    workflowCommand.orElseThrow(), modelName, reasoningEffort, generation);
        }
        return request.withConversation(conversationId, modelName, reasoningEffort);
    }

    AiConversationModelResponse update(ScoreUser requester, String conversationId,
                                       String requestedModelName,
                                       String requestedReasoningEffort,
                                       String traceparent, String tracestate) {
        AiChatConversationRepository repository = conversations.repository(requester);
        AiChatConversationSettings previous = repository.settingsForUpdate(conversationId);
        String modelName = models.resolveModelName(requestedModelName);
        String reasoningEffort = models.resolveReasoningEffort(
                modelName, requestedReasoningEffort);
        boolean modelChanged = !modelName.equals(previous.modelName());
        boolean contextCompacted = false;
        List<Message> history = conversations.history(requester, conversationId);
        Optional<AiContextBudget> targetBudget = contextBudgets.budget(modelName);
        long targetInputTokens = contextBudgets.estimateInputTokens(history, null, null);
        Optional<AiChatLatestUsage> latest = conversations.latestUsage(requester, conversationId);
        if (latest.isPresent() && (modelChanged || modelName.equals(latest.get().modelName()))) {
            targetInputTokens = Math.max(targetInputTokens, latest.get().inputTokens());
        }
        if (modelChanged && !history.isEmpty() && targetBudget.isPresent()
                && targetBudget.get().shouldCompact(targetInputTokens)) {
            String compactionRequestId = "model-switch-" + UUID.randomUUID();
            ChatRequest compactionRequest = new ChatRequest(ChatCommands.compactPrompt(),
                    compactionRequestId,
                    null, conversationId, null, List.of(), null, previous.modelName(),
                    previous.reasoningEffort(), "ask");
            ScoreAiObservability.Turn turn = observability.startExecution(
                    new ScoreAiObservability.ExecutionDescriptor(compactionRequestId,
                            conversationId, previous.modelName(),
                            "context_compaction", "none"),
                    requester, 0L, traceparent, tracestate);
            turn.executionStarted();
            try {
                Optional<AiContextBudget> sourceBudget =
                        contextBudgets.budget(previous.modelName());
                long sourceEstimate = contextBudgets.estimateInputTokens(
                        history, compactions.compactMessage(null), null);
                AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                        repository, objectMapper, requester, conversationId,
                        compactionRequestId, previous.modelName(), previous.reasoningEffort(),
                        ignored -> { }, sourceBudget.orElse(null), sourceEstimate,
                        observability.correlation(compactionRequestId),
                        ChatExecutionScopes.turn(compactionRequest, requester, 0L)
                                .withPurpose(ExecutionScope.Purpose.COMPACTION),
                        observer, observability);
                String summary = compactions.executeSummary(compactionRequest, history,
                        compactions.compactMessage(null), requester, recorder, false).content();
                compactions.replaceMemoryWithSummary(requester, conversationId, summary);
                long beforeTokens = targetInputTokens;
                history = List.of(compactions.summaryMessage(summary));
                targetInputTokens = contextBudgets.estimateInputTokens(history, null, null);
                journal.recordCompaction(requester, compactionRequest, beforeTokens,
                        targetInputTokens, summary, true, 0L);
                contextCompacted = true;
                journal.completeAfterTransaction(turn, "COMPLETED", null);
            } catch (RuntimeException failure) {
                journal.completeAfterTransaction(turn,
                        failure instanceof CancellationException ? "CANCELLED" : "FAILED",
                        failure);
                throw failure;
            }
        }
        if (targetBudget.isPresent()
                && targetBudget.get().exceedsSafeInput(targetInputTokens)) {
            throw new IllegalArgumentException(
                    "The existing conversation does not fit the selected model's safe context budget.");
        }
        AiChatConversationSettings updated =
                new AiChatConversationSettings(modelName, reasoningEffort);
        String settingsRequestId = "settings-update-" + UUID.randomUUID();
        ScoreAiObservability.Turn settingsTurn = observability.startExecution(
                new ScoreAiObservability.ExecutionDescriptor(settingsRequestId,
                        conversationId, modelName, "conversation_settings", "none",
                        reasoningEffort), requester, 0L, traceparent, tracestate);
        settingsTurn.executionStarted();
        try {
            journal.recordSettingsChange(requester, conversationId, settingsRequestId,
                    previous, updated, 0L);
            journal.completeAfterTransaction(settingsTurn, "COMPLETED", null);
        } catch (RuntimeException | Error failure) {
            journal.completeAfterTransaction(settingsTurn, "FAILED", failure);
            throw failure;
        }
        AiContextUsageInfo contextUsage = targetBudget.isPresent()
                ? targetBudget.get().usage(targetInputTokens, true,
                contextCompacted ? "post_compaction_estimate" : "model_switch_estimate")
                : null;
        return new AiConversationModelResponse(conversationId, modelName, reasoningEffort,
                contextCompacted, contextUsage);
    }
}

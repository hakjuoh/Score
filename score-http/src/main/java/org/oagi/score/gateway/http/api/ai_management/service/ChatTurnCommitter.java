package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationResultCommitter;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/** Commits memory, trajectory, and context-usage projections behind one result fence. */
final class ChatTurnCommitter {

    private final AiConversationUseCases conversations;
    private final ConversationCompactionSupport compactions;
    private final ChatConversationJournal journal;
    private final AiContextBudgetService contextBudgets;
    private final ConversationResultCommitter resultCommitter;
    private final AiRequestRegistry requests;

    ChatTurnCommitter(AiConversationUseCases conversations,
                      ConversationCompactionSupport compactions,
                      ChatConversationJournal journal,
                      AiContextBudgetService contextBudgets,
                      ConversationResultCommitter resultCommitter,
                      AiRequestRegistry requests) {
        this.conversations = conversations;
        this.compactions = compactions;
        this.journal = journal;
        this.contextBudgets = contextBudgets;
        this.resultCommitter = resultCommitter;
        this.requests = requests;
    }

    void commit(Command command) {
        Runnable persistence = () -> persist(command);
        try {
            commitResult(command.request().requestId(), persistence);
            recordCompactionUsage(command);
        } catch (RuntimeException failure) {
            command.recorder().sealAgainstLateCallbacks();
            throw failure;
        }
        command.recorder().sealAgainstLateCallbacks();
    }

    private void persist(Command command) {
        ChatRequest request = command.request();
        if (command.manualCompact()) {
            compactions.replaceMemoryWithSummary(
                    command.requester(), request.conversationId(), command.answer());
            long afterTokens = command.budget().map(value -> contextBudgets.estimateInputTokens(
                    List.of(compactions.summaryMessage(command.answer())), null, null)).orElse(0L);
            journal.recordCompaction(command.requester(), request,
                    command.initialProjectedInputTokens(), afterTokens,
                    command.answer(), false, command.generation());
        } else {
            persistConversationMessages(command);
        }
        command.recorder().recordAssistantMessage(command.answer(),
                projectionMetadata(command));
    }

    private void persistConversationMessages(Command command) {
        ChatRequest request = command.request();
        ChatAutomaticCompaction automatic = command.automaticCompaction();
        if (automatic.occurred()) {
            compactions.replaceChatMemory(command.requester(), request.conversationId(), List.of(
                    compactions.summaryMessage(automatic.summary()), command.userMessage(),
                    new AssistantMessage(command.answer())));
            journal.recordCompaction(command.requester(), request, automatic.beforeTokens(),
                    automatic.afterTokens(), automatic.summary(), true, command.generation());
        } else {
            ChatMemory memory = conversations.memory(command.requester());
            memory.add(request.conversationId(), command.userMessage());
            memory.add(request.conversationId(), new AssistantMessage(command.answer()));
        }
        command.repository().markExpanded(request.conversationId());
    }

    private void recordCompactionUsage(Command command) {
        if (command.manualCompact()) {
            long after = command.budget().map(value -> contextBudgets.estimateInputTokens(
                    List.of(compactions.summaryMessage(command.answer())), null, null)).orElse(0L);
            command.recorder().contextCompacted("manual",
                    command.initialProjectedInputTokens(),
                    command.budget().map(value -> value.usage(after, true,
                            "post_compaction_estimate")).orElse(null), false);
        } else if (command.automaticCompaction().occurred()) {
            ChatAutomaticCompaction automatic = command.automaticCompaction();
            command.recorder().contextCompacted("threshold", automatic.beforeTokens(),
                    command.budget().map(value -> value.usage(automatic.afterTokens(), true,
                            "post_compaction_estimate")).orElse(null), true);
        }
    }

    private Map<String, Object> projectionMetadata(Command command) {
        Map<String, Object> metadata = new LinkedHashMap<>(command.committedTrace());
        metadata.putAll(command.traceContext());
        metadata.put("ui_projection", true);
        metadata.put("permission_mode", command.permissionMode());
        metadata.put("multi_agent", command.request().multiAgent().asMap());
        if (command.request().activeWorkflow() != null) {
            metadata.put("active_workflow", command.request().activeWorkflow());
        }
        return Map.copyOf(metadata);
    }

    void commitResult(String requestId, Runnable persistence) {
        if (resultCommitter != null) {
            resultCommitter.commit(requestId, persistence);
        } else if (requests != null) {
            if (!requests.commitResult(requestId, persistence)) {
                throw new CancellationException(
                        "The assistant request stopped before its result was committed.");
            }
        } else {
            persistence.run();
        }
    }

    record Command(ChatRequest request, ScoreUser requester,
                   AiChatConversationRepository repository, UserMessage userMessage,
                   String answer, Map<String, Object> committedTrace,
                   Map<String, Object> traceContext, String permissionMode,
                   Optional<AiContextBudget> budget, boolean manualCompact,
                   long initialProjectedInputTokens,
                   ChatAutomaticCompaction automaticCompaction, long generation,
                   AiTrajectoryRecorder recorder) {
    }
}

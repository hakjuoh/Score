package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Commits regular-turn memory, trajectory, and automatic-compaction usage. */
final class ChatTurnCommitter {

    private final AiConversationUseCases conversations;
    private final ConversationCompactionSupport compactions;
    private final ChatConversationJournal journal;
    private final ChatResultCommitter results;

    ChatTurnCommitter(AiConversationUseCases conversations,
                      ConversationCompactionSupport compactions,
                      ChatConversationJournal journal,
                      ChatResultCommitter results) {
        this.conversations = conversations;
        this.compactions = compactions;
        this.journal = journal;
        this.results = results;
    }

    void commit(Command command) {
        Runnable persistence = () -> persist(command);
        try {
            results.commit(command.request().requestId(), persistence);
            recordCompactionUsage(command);
        } catch (RuntimeException failure) {
            command.recorder().sealAgainstLateCallbacks();
            throw failure;
        }
        command.recorder().sealAgainstLateCallbacks();
    }

    private void persist(Command command) {
        persistConversationMessages(command);
        command.recorder().recordAssistantMessage(command.answer(), ChatProjectionMetadata.assistant(
                command.committedTrace(), command.traceContext(), command.permissionMode(),
                command.request()));
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
        if (command.automaticCompaction().occurred()) {
            ChatAutomaticCompaction automatic = command.automaticCompaction();
            command.recorder().contextCompacted("threshold", automatic.beforeTokens(),
                    command.budget().map(value -> value.usage(automatic.afterTokens(), true,
                            "post_compaction_estimate")).orElse(null), true);
        }
    }

    record Command(ChatRequest request, ScoreUser requester,
                   AiChatConversationRepository repository, UserMessage userMessage,
                   String answer, Map<String, Object> committedTrace,
                   Map<String, Object> traceContext, String permissionMode,
                   Optional<AiContextBudget> budget,
                   ChatAutomaticCompaction automaticCompaction, long generation,
                   AiTrajectoryRecorder recorder) {
    }
}

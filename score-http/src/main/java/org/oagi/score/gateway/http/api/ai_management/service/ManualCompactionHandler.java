package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatResponse;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Executes and durably commits a user-requested conversation compaction. */
final class ManualCompactionHandler {

    private final ConversationCompactionSupport compactions;
    private final AiContextBudgetService contextBudgets;
    private final ChatConversationJournal journal;
    private final ChatResultCommitter results;
    private final ChatResponseFinalizer responses;

    ManualCompactionHandler(ConversationCompactionSupport compactions,
                            AiContextBudgetService contextBudgets,
                            ChatConversationJournal journal,
                            ChatResultCommitter results,
                            ChatResponseFinalizer responses) {
        this.compactions = compactions;
        this.contextBudgets = contextBudgets;
        this.journal = journal;
        this.results = results;
        this.responses = responses;
    }

    ChatResponse handle(Command command) {
        try {
            command.progress().accept("Compacting the conversation context.");
            AgentOutput summary = compactions.executeSummary(
                    command.request(), command.history(), command.compactMessage(),
                    command.requester(), command.recorder(), true, command.scope());
            ChatResponseFinalizer.FinalizedOutput output = responses.finalizeOutput(
                    ChatTurnOutput.disclosable(summary), command.request(), command.history(),
                    command.compactMessage(), command.requester(), command.recorder(),
                    command.scope());
            commit(command, output.answer(), output.committedTrace());
            return responses.response(output, command.request(), command.requester(),
                    command.scope(), command.progressMessages());
        } finally {
            command.recorder().sealAgainstLateCallbacks();
        }
    }

    private void commit(Command command, String answer,
                        Map<String, Object> committedTrace) {
        long afterTokens = command.budget().map(value -> contextBudgets.estimateInputTokens(
                List.of(compactions.summaryMessage(answer)), null, null)).orElse(0L);
        Runnable persistence = () -> {
            compactions.replaceMemoryWithSummary(command.requester(),
                    command.request().conversationId(), answer);
            journal.recordCompaction(command.requester(), command.request(),
                    command.initialProjectedInputTokens(), afterTokens,
                    answer, false, command.generation());
            command.recorder().recordAssistantMessage(answer,
                    ChatProjectionMetadata.assistant(committedTrace, command.traceContext(),
                            command.permissionMode(), command.request()));
        };
        results.commit(command.request().requestId(), persistence);
        command.recorder().contextCompacted("manual",
                command.initialProjectedInputTokens(),
                command.budget().map(value -> value.usage(afterTokens, true,
                        "post_compaction_estimate")).orElse(null), false);
    }

    record Command(ChatRequest request, ScoreUser requester, List<Message> history,
                   UserMessage compactMessage, ExecutionScope scope,
                   Optional<AiContextBudget> budget, long initialProjectedInputTokens,
                   Map<String, Object> traceContext, String permissionMode,
                   AiTrajectoryRecorder recorder, Consumer<String> progress,
                   List<String> progressMessages, long generation) {
    }
}

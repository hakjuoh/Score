package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatModelInfo;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiReasoningEffortInfo;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationSummary;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatHistoryMessage;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatLatestUsage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileDescriptor;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AtifTrajectoryService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Conversation query, export, and deletion use cases independent of turn execution. */
final class AiConversationUseCases {

    private final ScoreAiModelRegistry models;
    private final Function<ScoreUser, ChatMemory> memories;
    private final Function<ScoreUser, AiChatConversationRepository> repositories;
    private final AiContextBudgetService contextBudgets;
    private final AtifTrajectoryService trajectories;
    private final ToolSearchToolCallingAdvisor toolSessions;
    private final AiFileService files;

    AiConversationUseCases(ScoreAiModelRegistry models,
                           Function<ScoreUser, ChatMemory> memories,
                           Function<ScoreUser, AiChatConversationRepository> repositories,
                           AiContextBudgetService contextBudgets,
                           AtifTrajectoryService trajectories,
                           ToolSearchToolCallingAdvisor toolSessions,
                           AiFileService files) {
        this.models = Objects.requireNonNull(models, "models");
        this.memories = memories;
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.contextBudgets = Objects.requireNonNull(contextBudgets, "contextBudgets");
        this.trajectories = Objects.requireNonNull(trajectories, "trajectories");
        this.toolSessions = toolSessions;
        this.files = files;
    }

    List<ChatConversationSummary> list(ScoreUser requester) {
        return repository(requester).list();
    }

    ChatConversationDetails get(ScoreUser requester, String conversationId) {
        ChatConversationDetails details = repository(requester).get(conversationId);
        if (files != null) {
            List<ChatHistoryMessage> messages = details.messages().stream()
                    .map(message -> attachFiles(requester, conversationId, message)).toList();
            details = new ChatConversationDetails(details.conversationId(), details.title(),
                    details.modelName(), details.reasoningEffort(), details.updatedAt(), messages,
                    details.contextMessages(), details.contextUsage(), details.permissionMode(),
                    normalizeWorkflowPreference(details.activeWorkflow()));
        }
        AiContextUsageInfo usage = currentContextUsage(requester, conversationId, details.modelName());
        return new ChatConversationDetails(details.conversationId(), details.title(),
                details.modelName(), details.reasoningEffort(), details.updatedAt(), details.messages(),
                details.contextMessages(), usage, details.permissionMode(),
                normalizeWorkflowPreference(details.activeWorkflow()));
    }

    List<AiChatModelInfo> availableModels() {
        return models.availableModels().stream()
                .map(model -> new AiChatModelInfo(
                        model.name(), model.displayName(), model.provider(), model.defaultModel(),
                        model.description(), model.defaultReasoningEffort(),
                        model.reasoningEfforts().stream()
                                .map(effort -> new AiReasoningEffortInfo(
                                        effort.name(), effort.displayName(), effort.description()))
                                .toList(), model.contextBudget().contextWindow(),
                        model.contextBudget().outputReserveTokens(),
                        model.contextBudget().autoCompactThresholdTokens(),
                        model.contextBudget().emergencyHeadroomTokens()))
                .toList();
    }

    Map<String, Object> trajectory(ScoreUser requester, String conversationId) {
        String version = ChatService.class.getPackage().getImplementationVersion();
        AiChatConversationRepository repository = repository(requester);
        AiChatTrajectoryData data = repository.getTrajectoryData(conversationId);
        return trajectories.export(data, StringUtils.hasText(version) ? version : "3.6.0-dev",
                repository.modelName(conversationId));
    }

    boolean delete(ScoreUser requester, String conversationId) {
        if (files != null) files.deleteConversationFiles(requester, conversationId);
        boolean deleted = repository(requester).delete(conversationId);
        if (deleted) {
            ChatMemory memory = memory(requester);
            if (memory != null) memory.clear(conversationId);
            if (toolSessions != null) toolSessions.evictSession(conversationId);
        }
        return deleted;
    }

    List<Message> history(ScoreUser requester, String conversationId) {
        ChatMemory memory = memory(requester);
        if (memory == null || !StringUtils.hasText(conversationId)) return List.of();
        List<Message> messages = memory.get(conversationId);
        return messages != null ? List.copyOf(messages) : List.of();
    }

    Optional<AiChatLatestUsage> latestUsage(ScoreUser requester, String conversationId) {
        AiChatConversationRepository repository = repository(requester);
        if (repository == null) return Optional.empty();
        Optional<AiChatLatestUsage> usage = repository.latestUsage(conversationId);
        return usage != null ? usage : Optional.empty();
    }

    ChatMemory memory(ScoreUser requester) {
        return memories != null ? memories.apply(requester) : null;
    }

    AiChatConversationRepository repository(ScoreUser requester) {
        return repositories.apply(requester);
    }

    static String normalizeWorkflowPreference(String value) {
        if (!StringUtils.hasText(value)) return null;
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "agents" -> "agents";
            case "assistant" -> "assistant";
            default -> null;
        };
    }

    private ChatHistoryMessage attachFiles(ScoreUser requester, String conversationId,
                                           ChatHistoryMessage message) {
        if (!"assistant".equals(message.role()) || !StringUtils.hasText(message.requestId())) {
            return message;
        }
        List<AiFileDescriptor> attached = files.findByRequest(
                requester, conversationId, message.requestId());
        return new ChatHistoryMessage(message.index(), message.role(), message.content(),
                message.requestId(), message.turnId(), message.groupId(), message.toolCallId(),
                message.toolCallSequence(), message.subtype(), message.visibility(), attached,
                message.metadata());
    }

    private AiContextUsageInfo currentContextUsage(ScoreUser requester, String conversationId,
                                                   String modelName) {
        Optional<AiContextBudget> budget = contextBudgets.budget(modelName);
        if (budget.isEmpty()) return null;
        Optional<AiChatLatestUsage> latest = latestUsage(requester, conversationId);
        if (latest.isPresent() && modelName.equals(latest.get().modelName())) {
            return budget.get().usage(latest.get().inputTokens(), latest.get().estimated(),
                    "stored_provider");
        }
        long estimate = contextBudgets.estimateInputTokens(
                history(requester, conversationId), null, null);
        return budget.get().usage(estimate, true, "restore_estimate");
    }
}

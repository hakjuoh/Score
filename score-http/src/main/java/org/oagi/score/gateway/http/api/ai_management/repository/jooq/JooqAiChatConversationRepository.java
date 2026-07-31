package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationSummary;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatHistoryMessage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationSettings;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatLatestUsage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transactional facade for requester-owned AI conversations.
 * Query projection, mutation, and ownership concerns are delegated to focused collaborators.
 */
public class JooqAiChatConversationRepository extends JooqBaseRepository
        implements AiChatConversationRepository {

    private final JooqAiChatConversationAccess access;
    private final JooqAiChatConversationCommands commands;
    private final JooqAiChatConversationQueries queries;

    public JooqAiChatConversationRepository(DSLContext dslContext, ScoreUser requester,
                                            RepositoryFactory repositoryFactory,
                                            AiChatJsonSerializer serializer) {
        super(dslContext, requester, repositoryFactory);
        AiChatJsonSerializer requiredSerializer = Objects.requireNonNull(
                serializer, "serializer must not be null");
        this.access = new JooqAiChatConversationAccess(dslContext,
                ULong.valueOf(requester.userId().value()));
        this.commands = new JooqAiChatConversationCommands(
                dslContext, access, requiredSerializer);
        this.queries = new JooqAiChatConversationQueries(
                dslContext, access, requiredSerializer);
    }

    @Override
    @Transactional
    public String open(String requestedConversationId, String firstPrompt) {
        return commands.open(requestedConversationId, firstPrompt);
    }

    @Override
    @Transactional
    public String openChild(String parentConversationId, String parentRequestId,
                            AiChatConversationKind kind, String workerId, String firstPrompt) {
        return commands.openChild(parentConversationId, parentRequestId, kind, workerId, firstPrompt);
    }

    @Override
    @Transactional(readOnly = true)
    public String modelName(String conversationId) {
        return queries.modelName(conversationId);
    }

    @Override
    @Transactional(readOnly = true)
    public AiChatConversationSettings settings(String conversationId) {
        return queries.settings(conversationId);
    }

    @Override
    @Transactional
    public AiChatConversationSettings settingsForUpdate(String conversationId) {
        return queries.latestSettings(access.lockOwned(conversationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> activeWorkflow(String conversationId) {
        return queries.activeWorkflow(conversationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiChatLatestUsage> latestUsage(String conversationId) {
        return queries.latestUsage(conversationId);
    }

    @Override
    @Transactional
    public AiChatStoredStep append(String conversationId, AiChatTrajectoryStep step) {
        return commands.append(conversationId, step);
    }

    @Override
    @Transactional
    public void updateModelCall(String conversationId, long stepId, AiChatTrajectoryStep step) {
        commands.updateModelCall(conversationId, stepId, step);
    }

    @Override
    @Transactional
    public void updateObservation(String conversationId, long stepId,
                                  Map<String, Object> observation) {
        commands.updateObservation(conversationId, stepId, observation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatConversationSummary> list() {
        return queries.list();
    }

    @Override
    @Transactional(readOnly = true)
    public ChatConversationDetails get(String conversationId) {
        return queries.get(conversationId);
    }

    @Override
    @Transactional(readOnly = true)
    public AiChatTrajectoryData getTrajectoryData(String conversationId) {
        return queries.getTrajectoryData(conversationId);
    }

    @Override
    @Transactional
    public void markCompacted(String conversationId) {
        commands.setCompacted(conversationId, true);
    }

    @Override
    @Transactional
    public void markExpanded(String conversationId) {
        commands.setCompacted(conversationId, false);
    }

    @Override
    @Transactional
    public boolean delete(String conversationId) {
        return commands.delete(conversationId);
    }

    static List<ChatHistoryMessage> coalesceFinalWorkflowResult(
            List<ChatHistoryMessage> messages) {
        return JooqAiChatConversationQueries.coalesceFinalWorkflowResult(messages);
    }

    static void validateStoredSettingsChange(String messageKind, String modelName,
                                             String reasoningEffort) {
        JooqAiChatConversationQueries.validateStoredSettingsChange(
                messageKind, modelName, reasoningEffort);
    }
}

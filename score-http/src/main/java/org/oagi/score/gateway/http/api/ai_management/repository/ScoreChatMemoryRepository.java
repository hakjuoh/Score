package org.oagi.score.gateway.http.api.ai_management.repository;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationSummary;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationSettings;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatLatestUsage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence contract for connectCenter-owned AI conversations, bounded model
 * memory, and the complete UI/audit trajectory.
 */
public interface ScoreChatMemoryRepository extends ChatMemoryRepository {

    /**
     * Opens an existing owned conversation or creates a new conversation.
     */
    String open(ScoreUser requester, String requestedConversationId, String firstPrompt);

    /**
     * Returns the latest model selected for an owned conversation.
     */
    String modelName(ScoreUser requester, String conversationId);

    /**
     * Returns the latest complete settings snapshot for an owned conversation.
     */
    AiChatConversationSettings settings(ScoreUser requester, String conversationId);

    /**
     * Locks an owned conversation and returns its latest complete settings snapshot.
     */
    AiChatConversationSettings settingsForUpdate(ScoreUser requester, String conversationId);

    /**
     * Returns the latest recorded context-usage measurement.
     */
    Optional<AiChatLatestUsage> latestUsage(ScoreUser requester, String conversationId);

    /**
     * Appends a trajectory step while maintaining conversation sequence and update time.
     */
    AiChatStoredStep append(ScoreUser requester, String conversationId, AiChatTrajectoryStep step);

    /**
     * Updates the observation associated with an owned trajectory step.
     */
    void updateObservation(ScoreUser requester, String conversationId, long stepId,
                           Map<String, Object> observation);

    /**
     * Lists the requester's most recent conversations.
     */
    List<ChatConversationSummary> list(ScoreUser requester);

    /**
     * Loads the UI history and settings for an owned conversation.
     */
    ChatConversationDetails get(ScoreUser requester, String conversationId);

    /**
     * Exports the bounded conversation trajectory in ATIF-compatible form.
     */
    Map<String, Object> trajectory(ScoreUser requester, String conversationId,
                                   String agentVersion, String defaultModel);

    /**
     * Marks an owned conversation's model context as compacted.
     */
    void markCompacted(ScoreUser requester, String conversationId);

    /**
     * Marks an owned conversation's model context as expanded.
     */
    void markExpanded(ScoreUser requester, String conversationId);

    /**
     * Deletes conversations whose last activity predates the cutoff.
     */
    int deleteExpiredConversations(Instant cutoff);

    /**
     * Expires active mutation confirmations at or before the supplied time.
     */
    int expireMutationConfirmations(Instant now);

    /**
     * Deletes an owned conversation and its dependent records.
     */
    boolean delete(ScoreUser requester, String conversationId);
}

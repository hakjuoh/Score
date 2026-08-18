package org.oagi.score.gateway.http.api.ai_management.repository;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationSummary;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationSettings;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatLatestUsage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStepId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence contract for requester-owned AI conversations and their complete
 * UI/audit trajectories.
 */
public interface AiChatConversationRepository {

    /**
     * Opens an existing owned conversation or creates a new conversation.
     */
    String open(String requestedConversationId, String firstPrompt);

    /** Creates an independently traced parallel or sub-agent child conversation. */
    String openChild(String parentConversationId, String parentRequestId,
                     AiChatConversationKind kind, String workerId, String title);

    /**
     * Returns the latest model selected for an owned conversation.
     */
    String modelName(String conversationId);

    /**
     * Returns the latest complete settings snapshot for an owned conversation.
     */
    AiChatConversationSettings settings(String conversationId);

    /**
     * Locks an owned conversation and returns its latest complete settings snapshot.
     */
    AiChatConversationSettings settingsForUpdate(String conversationId);

    /** Returns the conversation's forced workflow, or empty when workflow selection is automatic. */
    Optional<String> activeWorkflow(String conversationId);

    /**
     * Returns the latest recorded context-usage measurement.
     */
    Optional<AiChatLatestUsage> latestUsage(String conversationId);

    /**
     * Appends a trajectory step while maintaining conversation sequence and update time.
     */
    AiChatStoredStep append(String conversationId, AiChatTrajectoryStep step);

    /** Completes a model-call row whose sequence was reserved when the provider call began. */
    void updateModelCall(String conversationId, AiChatStepId stepId, AiChatTrajectoryStep step);

    /**
     * Updates the observation associated with an owned trajectory step.
     */
    void updateObservation(String conversationId, AiChatStepId stepId,
                           Map<String, Object> observation);

    /**
     * Lists the requester's most recent conversations.
     */
    List<ChatConversationSummary> list();

    /**
     * Loads the UI history and settings for an owned conversation.
     */
    ChatConversationDetails get(String conversationId);

    /**
     * Loads the complete stored trajectory for an owned conversation.
     */
    AiChatTrajectoryData getTrajectoryData(String conversationId);

    /**
     * Marks an owned conversation's model context as compacted.
     */
    void markCompacted(String conversationId);

    /**
     * Marks an owned conversation's model context as expanded.
     */
    void markExpanded(String conversationId);

    /**
     * Updates the title of an owned conversation.
     */
    void updateTitle(String conversationId, String title);

    /**
     * Deletes an owned conversation and its dependent records.
     */
    boolean delete(String conversationId);
}

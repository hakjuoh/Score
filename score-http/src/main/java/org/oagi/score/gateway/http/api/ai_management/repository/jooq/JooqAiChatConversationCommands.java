package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStepId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiChatConversationRecord;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiChatStepRecord;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.max;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatStep.AI_CHAT_STEP;

/** Owns mutations of conversation metadata and trajectory rows. */
final class JooqAiChatConversationCommands {

    private final DSLContext dslContext;
    private final JooqAiChatConversationAccess access;
    private final AiChatJsonSerializer serializer;

    JooqAiChatConversationCommands(DSLContext dslContext,
                                   JooqAiChatConversationAccess access,
                                   AiChatJsonSerializer serializer) {
        this.dslContext = dslContext;
        this.access = access;
        this.serializer = serializer;
    }

    String open(String requestedConversationId, String firstPrompt) {
        if (StringUtils.hasText(requestedConversationId)) {
            AiChatConversationId internalConversationId = access.lockOwned(requestedConversationId);
            dslContext.update(AI_CHAT_CONVERSATION)
                    .set(AI_CHAT_CONVERSATION.LAST_UPDATE_TIMESTAMP, LocalDateTime.now())
                    .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                            .eq(access.valueOf(internalConversationId)))
                    .execute();
            return requestedConversationId;
        }
        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        AiChatConversationRecord record = new AiChatConversationRecord();
        record.setGuid(conversationId);
        record.setAppUserId(access.valueOf(access.userId()));
        record.setTitle(title(firstPrompt));
        record.setCompacted((byte) 0);
        record.setCreationTimestamp(now);
        record.setLastUpdateTimestamp(now);
        dslContext.insertInto(AI_CHAT_CONVERSATION).set(record).execute();
        return conversationId;
    }

    String openChild(String parentConversationId, String parentRequestId,
                     AiChatConversationKind kind, String workerId, String firstPrompt) {
        if (kind == null || !kind.isChild() || !StringUtils.hasText(parentRequestId)
                || !StringUtils.hasText(workerId)) {
            throw new IllegalArgumentException("Child execution conversation identity is incomplete.");
        }
        AiChatConversationId parentId = access.lockOwned(parentConversationId);
        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        dslContext.insertInto(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.GUID, conversationId)
                .set(AI_CHAT_CONVERSATION.APP_USER_ID, access.valueOf(access.userId()))
                .set(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID,
                        access.valueOf(parentId))
                .set(AI_CHAT_CONVERSATION.CONVERSATION_KIND, kind.name())
                .set(AI_CHAT_CONVERSATION.AGENT_ID, workerId.strip())
                .set(AI_CHAT_CONVERSATION.PARENT_REQUEST_ID, parentRequestId.strip())
                .set(AI_CHAT_CONVERSATION.TITLE, title(firstPrompt))
                .set(AI_CHAT_CONVERSATION.COMPACTED, (byte) 0)
                .set(AI_CHAT_CONVERSATION.CREATION_TIMESTAMP, now)
                .set(AI_CHAT_CONVERSATION.LAST_UPDATE_TIMESTAMP, now)
                .execute();
        return conversationId;
    }

    AiChatStoredStep append(String conversationId, AiChatTrajectoryStep step) {
        AiChatConversationId internalConversationId = access.lockOwned(conversationId);
        Long nextSequence = dslContext.select(
                        coalesce(max(AI_CHAT_STEP.STEP_SEQUENCE), -1L).add(1L))
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId)))
                .fetchOneInto(Long.class);
        long sequence = nextSequence != null ? nextSequence : 0L;
        Instant createdAt = step.createdAt() != null ? step.createdAt() : Instant.now();
        AiChatStepRecord stepRecord = new AiChatStepRecord();
        stepRecord.setAiChatConversationId(access.valueOf(internalConversationId));
        stepRecord.setStepSequence(sequence);
        stepRecord.setRequestId(blankToNull(step.requestId()));
        stepRecord.setSource(step.source());
        stepRecord.setMessageKind(step.messageKind());
        stepRecord.setVisibility(step.visibility());
        stepRecord.setMessage(Objects.requireNonNullElse(step.message(), ""));
        stepRecord.setReasoningContent(blankToNull(step.reasoningContent()));
        stepRecord.setModelName(blankToNull(step.modelName()));
        stepRecord.setReasoningEffort(blankToNull(step.reasoningEffort()));
        stepRecord.setToolCallsJson(serializer.serialize(step.toolCalls()));
        stepRecord.setObservationJson(serializer.serialize(step.observation()));
        stepRecord.setMetricsJson(serializer.serialize(step.metrics()));
        stepRecord.setExtraJson(serializer.serialize(step.extra()));
        stepRecord.setLlmCallCount(step.llmCallCount());
        stepRecord.setIsCopiedContext(byteValue(step.isCopiedContext()));
        stepRecord.setCreationTimestamp(localDateTime(createdAt));
        AiChatStepId id = new AiChatStepId(dslContext.insertInto(AI_CHAT_STEP)
                .set(stepRecord)
                .returningResult(AI_CHAT_STEP.AI_CHAT_STEP_ID)
                .fetchSingle(AI_CHAT_STEP.AI_CHAT_STEP_ID).toBigInteger());
        dslContext.update(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.LAST_UPDATE_TIMESTAMP, localDateTime(createdAt))
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId)))
                .execute();
        return new AiChatStoredStep(id, sequence, createdAt);
    }

    void updateObservation(String conversationId, AiChatStepId stepId,
                           Map<String, Object> observation) {
        AiChatConversationId internalConversationId = access.lockOwned(conversationId);
        int updated = dslContext.update(AI_CHAT_STEP)
                .set(AI_CHAT_STEP.OBSERVATION_JSON, serializer.serialize(observation))
                .where(AI_CHAT_STEP.AI_CHAT_STEP_ID.eq(access.valueOf(stepId))
                        .and(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                                .eq(access.valueOf(internalConversationId))))
                .execute();
        if (updated != 1) throw new IllegalArgumentException(
                "The trajectory step no longer exists.");
    }

    void updateModelCall(String conversationId, AiChatStepId stepId,
                         AiChatTrajectoryStep step) {
        AiChatConversationId internalConversationId = access.lockOwned(conversationId);
        int updated = dslContext.update(AI_CHAT_STEP)
                .set(AI_CHAT_STEP.MODEL_NAME, blankToNull(step.modelName()))
                .set(AI_CHAT_STEP.REASONING_EFFORT, blankToNull(step.reasoningEffort()))
                .set(AI_CHAT_STEP.TOOL_CALLS_JSON, serializer.serialize(step.toolCalls()))
                .set(AI_CHAT_STEP.OBSERVATION_JSON, serializer.serialize(step.observation()))
                .set(AI_CHAT_STEP.METRICS_JSON, serializer.serialize(step.metrics()))
                .set(AI_CHAT_STEP.EXTRA_JSON, serializer.serialize(step.extra()))
                .set(AI_CHAT_STEP.LLM_CALL_COUNT, step.llmCallCount())
                .where(AI_CHAT_STEP.AI_CHAT_STEP_ID.eq(access.valueOf(stepId))
                        .and(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                                .eq(access.valueOf(internalConversationId)))
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("model_call")))
                .execute();
        if (updated != 1) throw new IllegalArgumentException(
                "The model-call trajectory step no longer exists.");
    }

    void setCompacted(String conversationId, boolean compacted) {
        AiChatConversationId internalConversationId = access.lockOwned(conversationId);
        dslContext.update(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.COMPACTED, byteValue(compacted))
                .set(AI_CHAT_CONVERSATION.LAST_UPDATE_TIMESTAMP, LocalDateTime.now())
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId)))
                .execute();
    }

    boolean delete(String conversationId) {
        access.requireOwned(conversationId);
        return dslContext.deleteFrom(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID
                                .eq(access.valueOf(access.userId()))))
                .execute() > 0;
    }

    private String title(String prompt) {
        String value = StringUtils.hasText(prompt)
                ? prompt.strip().replaceAll("\\s+", " ") : "New conversation";
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private LocalDateTime localDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Byte byteValue(Boolean value) {
        return value == null ? null : byteValue(value.booleanValue());
    }

    private byte byteValue(boolean value) {
        return (byte) (value ? 1 : 0);
    }
}

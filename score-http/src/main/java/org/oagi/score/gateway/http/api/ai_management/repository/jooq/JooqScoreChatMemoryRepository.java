package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatContextMessage;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationSummary;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatHistoryMessage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationSettings;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatLatestUsage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.ScoreChatMemoryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiChatConversationRecord;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiChatMemoryRecord;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiChatStepRecord;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.SQLDataType.BIGINTUNSIGNED;
import static org.jooq.impl.SQLDataType.VARCHAR;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatMemory.AI_CHAT_MEMORY;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatMutationConfirmation.AI_CHAT_MUTATION_CONFIRMATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatStep.AI_CHAT_STEP;

/**
 * connectCenter-owned conversation repository.
 *
 * The bounded model context and the complete UI/audit trajectory are intentionally
 * separate. The trajectory rows retain the ATIF v1.7 step fields needed to replay model
 * calls, reasoning, tool calls, observations, and token metrics.
 */
@Repository
public class JooqScoreChatMemoryRepository implements ScoreChatMemoryRepository {

    private static final int MAX_CONVERSATION_LIST = 100;
    private static final int MAX_HISTORY_STEPS = 500;
    private static final int MAX_TRAJECTORY_STEPS = 5000;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeReference<>() {};
    // These columns are introduced by V3_6_1. Plain fields keep this repository
    // buildable before a developer regenerates the checked-in jOOQ sources.
    private static final Field<ULong> PARENT_CONVERSATION_ID = field(
            name("ai_chat_conversation", "parent_ai_chat_conversation_id"), BIGINTUNSIGNED);
    private static final Field<String> CONVERSATION_KIND = field(
            name("ai_chat_conversation", "conversation_kind"), VARCHAR(16));
    private static final Field<String> AGENT_ID = field(
            name("ai_chat_conversation", "agent_id"), VARCHAR(64));
    private static final Field<String> PARENT_REQUEST_ID = field(
            name("ai_chat_conversation", "parent_request_id"), VARCHAR(128));

    private final DSLContext dslContext;
    private final ObjectMapper objectMapper;

    /**
     * Creates the conversation repository.
     *
     * @param dslContext context used to execute generated-model queries and commands
     * @param objectMapper mapper for trajectory and model-memory metadata
     */
    public JooqScoreChatMemoryRepository(DSLContext dslContext, ObjectMapper objectMapper) {
        this.dslContext = dslContext;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public String open(ScoreUser requester, String requestedConversationId, String firstPrompt) {
        if (StringUtils.hasText(requestedConversationId)) {
            ULong internalConversationId = lockOwned(requester, requestedConversationId);
            dslContext.update(AI_CHAT_CONVERSATION)
                    .set(AI_CHAT_CONVERSATION.UPDATED_AT, LocalDateTime.now())
                    .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                    .execute();
            return requestedConversationId;
        }
        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        AiChatConversationRecord record = new AiChatConversationRecord();
        record.setGuid(conversationId);
        record.setAppUserId(userId(requester));
        record.setTitle(title(firstPrompt));
        record.setCompacted((byte) 0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        dslContext.insertInto(AI_CHAT_CONVERSATION).set(record).execute();
        return conversationId;
    }

    @Override
    @Transactional
    public String openChild(ScoreUser requester, String parentConversationId,
                            String parentRequestId, AiChatConversationKind kind,
                            String workerId, String firstPrompt) {
        if (kind == null || !kind.isChild() || !StringUtils.hasText(parentRequestId)
                || !StringUtils.hasText(workerId)) {
            throw new IllegalArgumentException("Child execution conversation identity is incomplete.");
        }
        ULong parentId = lockOwned(requester, parentConversationId);
        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        dslContext.insertInto(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.GUID, conversationId)
                .set(AI_CHAT_CONVERSATION.APP_USER_ID, userId(requester))
                .set(PARENT_CONVERSATION_ID, parentId)
                .set(CONVERSATION_KIND, kind.name())
                .set(AGENT_ID, workerId.strip())
                .set(PARENT_REQUEST_ID, parentRequestId.strip())
                .set(AI_CHAT_CONVERSATION.TITLE, title(firstPrompt))
                .set(AI_CHAT_CONVERSATION.COMPACTED, (byte) 0)
                .set(AI_CHAT_CONVERSATION.CREATED_AT, now)
                .set(AI_CHAT_CONVERSATION.UPDATED_AT, now)
                .execute();
        return conversationId;
    }

    @Override
    @Transactional(readOnly = true)
    public String modelName(ScoreUser requester, String conversationId) {
        return settings(requester, conversationId).modelName();
    }

    @Override
    @Transactional(readOnly = true)
    public AiChatConversationSettings settings(ScoreUser requester, String conversationId) {
        return latestSettings(ownedId(requester, conversationId));
    }

    @Override
    @Transactional
    public AiChatConversationSettings settingsForUpdate(ScoreUser requester, String conversationId) {
        return latestSettings(lockOwned(requester, conversationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> activeWorkflow(ScoreUser requester, String conversationId) {
        ULong internalConversationId = ownedId(requester, conversationId);
        return dslContext.select(AI_CHAT_STEP.EXTRA_JSON)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("workflow_preference")))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(AI_CHAT_STEP.EXTRA_JSON)
                .flatMap(value -> Optional.ofNullable(mapOrEmpty(value).get("activeWorkflow")))
                .map(Object::toString)
                .filter(StringUtils::hasText);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiChatLatestUsage> latestUsage(ScoreUser requester, String conversationId) {
        ULong internalConversationId = ownedId(requester, conversationId);
        return dslContext.select(AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.METRICS_JSON,
                        AI_CHAT_STEP.CREATED_AT)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                        .and(AI_CHAT_STEP.METRICS_JSON.isNotNull())
                        // Fan-out subagent calls carry transient prompts; only
                        // conversation-scoped metrics may drive the context floor.
                        .and(AI_CHAT_STEP.METRICS_JSON.notLike("%\"context_scope\"%")))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(record -> {
            Map<String, Object> metrics = mapOrEmpty(record.get(AI_CHAT_STEP.METRICS_JSON));
            long inputTokens = number(metrics.get("context_input_tokens"));
            if (inputTokens <= 0) inputTokens = number(metrics.get("prompt_tokens"));
            return new AiChatLatestUsage(record.get(AI_CHAT_STEP.MODEL_NAME), inputTokens,
                    Boolean.TRUE.equals(metrics.get("context_estimated")),
                    instant(record.get(AI_CHAT_STEP.CREATED_AT)));
        });
    }

    @Override
    @Transactional
    public AiChatStoredStep append(ScoreUser requester, String conversationId,
                                   AiChatTrajectoryStep step) {
        ULong internalConversationId = lockOwned(requester, conversationId);
        Long nextSequence = dslContext.select(
                        coalesce(max(AI_CHAT_STEP.STEP_SEQUENCE), -1L).add(1L))
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .fetchOneInto(Long.class);
        long sequence = nextSequence != null ? nextSequence : 0L;
        Instant createdAt = step.createdAt() != null ? step.createdAt() : Instant.now();
        AiChatStepRecord stepRecord = new AiChatStepRecord();
        stepRecord.setAiChatConversationId(internalConversationId);
        stepRecord.setStepSequence(sequence);
        stepRecord.setRequestId(blankToNull(step.requestId()));
        stepRecord.setSource(step.source());
        stepRecord.setMessageKind(step.messageKind());
        stepRecord.setVisibility(step.visibility());
        stepRecord.setMessage(Objects.requireNonNullElse(step.message(), ""));
        stepRecord.setReasoningContent(blankToNull(step.reasoningContent()));
        stepRecord.setModelName(blankToNull(step.modelName()));
        stepRecord.setReasoningEffort(blankToNull(step.reasoningEffort()));
        stepRecord.setAgentRuntime(blankToNull(step.runtime()));
        stepRecord.setRuntimeOptionsJson(jsonOrNull(step.runtimeOptions()));
        stepRecord.setToolCallsJson(jsonOrNull(step.toolCalls()));
        stepRecord.setObservationJson(jsonOrNull(step.observation()));
        stepRecord.setMetricsJson(jsonOrNull(step.metrics()));
        stepRecord.setExtraJson(jsonOrNull(step.extra()));
        stepRecord.setLlmCallCount(step.llmCallCount());
        stepRecord.setIsCopiedContext(byteValue(step.isCopiedContext()));
        stepRecord.setCreatedAt(localDateTime(createdAt));
        ULong id = dslContext.insertInto(AI_CHAT_STEP)
                .set(stepRecord)
                .returningResult(AI_CHAT_STEP.AI_CHAT_STEP_ID)
                .fetchSingle(AI_CHAT_STEP.AI_CHAT_STEP_ID);
        dslContext.update(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.UPDATED_AT, localDateTime(createdAt))
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .execute();
        return new AiChatStoredStep(id.longValue(), sequence, createdAt);
    }

    @Override
    @Transactional
    public void updateObservation(ScoreUser requester, String conversationId, long stepId,
                                  Map<String, Object> observation) {
        ULong internalConversationId = lockOwned(requester, conversationId);
        int updated = dslContext.update(AI_CHAT_STEP)
                .set(AI_CHAT_STEP.OBSERVATION_JSON, jsonOrNull(observation))
                .where(AI_CHAT_STEP.AI_CHAT_STEP_ID.eq(ULong.valueOf(stepId))
                        .and(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)))
                .execute();
        if (updated != 1) {
            throw new IllegalArgumentException("The trajectory step no longer exists.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatConversationSummary> list(ScoreUser requester) {
        var messageCount = count(AI_CHAT_STEP.AI_CHAT_STEP_ID)
                .filterWhere(AI_CHAT_STEP.MESSAGE_KIND.in("user", "assistant", "error"))
                .as("message_count");
        return dslContext.select(AI_CHAT_CONVERSATION.GUID, AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.COMPACTED, AI_CHAT_CONVERSATION.CREATED_AT,
                        AI_CHAT_CONVERSATION.UPDATED_AT, messageCount)
                .from(AI_CHAT_CONVERSATION)
                .leftJoin(AI_CHAT_STEP).on(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID))
                .where(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId(requester))
                        .and(PARENT_CONVERSATION_ID.isNull()))
                .groupBy(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_CONVERSATION.GUID, AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.COMPACTED, AI_CHAT_CONVERSATION.CREATED_AT,
                        AI_CHAT_CONVERSATION.UPDATED_AT)
                .orderBy(AI_CHAT_CONVERSATION.UPDATED_AT.desc())
                .limit(MAX_CONVERSATION_LIST)
                .fetch(record -> new ChatConversationSummary(
                        record.get(AI_CHAT_CONVERSATION.GUID),
                        record.get(AI_CHAT_CONVERSATION.TITLE),
                        record.get(messageCount),
                        Boolean.TRUE.equals(byteBoolean(record.get(AI_CHAT_CONVERSATION.COMPACTED))),
                        instant(record.get(AI_CHAT_CONVERSATION.CREATED_AT)),
                        instant(record.get(AI_CHAT_CONVERSATION.UPDATED_AT))));
    }

    @Override
    @Transactional(readOnly = true)
    public ChatConversationDetails get(ScoreUser requester, String conversationId) {
        ULong internalConversationId = ownedId(requester, conversationId);
        Header header = dslContext.select(AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.UPDATED_AT)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .fetchSingle(record -> new Header(record.get(AI_CHAT_CONVERSATION.TITLE),
                        instant(record.get(AI_CHAT_CONVERSATION.UPDATED_AT))));
        AiChatConversationSettings settings = latestSettings(internalConversationId);
        List<ULong> childIds = dslContext.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(PARENT_CONVERSATION_ID.eq(internalConversationId))
                .fetch(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        var visibleChildKinds = AI_CHAT_STEP.MESSAGE_KIND.in(
                "agent_lifecycle", "tool_call", "tool_call_update", "guide");
        List<ChatHistoryMessage> messages = dslContext.select(
                        AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID, AI_CHAT_STEP.STEP_SEQUENCE,
                        AI_CHAT_STEP.REQUEST_ID,
                        AI_CHAT_STEP.MESSAGE_KIND, AI_CHAT_STEP.VISIBILITY,
                        AI_CHAT_STEP.MESSAGE, AI_CHAT_STEP.REASONING_CONTENT,
                        AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.TOOL_CALLS_JSON,
                        AI_CHAT_STEP.OBSERVATION_JSON, AI_CHAT_STEP.EXTRA_JSON,
                        AI_CHAT_STEP.CREATED_AT)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                        .or(childIds.isEmpty() ? org.jooq.impl.DSL.falseCondition()
                                : AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.in(childIds)
                                        .and(visibleChildKinds)))
                .orderBy(AI_CHAT_STEP.CREATED_AT.desc(), AI_CHAT_STEP.AI_CHAT_STEP_ID.desc())
                .limit(MAX_HISTORY_STEPS)
                .fetch(this::historyMessage);
        Collections.reverse(messages);
        List<ChatHistoryMessage> indexedMessages = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            indexedMessages.add(withIndex(messages.get(index), index));
        }
        return new ChatConversationDetails(conversationId, header.title(), settings.modelName(),
                settings.reasoningEffort(), settings.runtime(), settings.runtimeOptions(), header.updatedAt(),
                indexedMessages, List.<ChatContextMessage>of());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> trajectory(ScoreUser requester, String conversationId,
                                          String agentVersion, String defaultModel) {
        ULong internalConversationId = ownedId(requester, conversationId);
        List<ULong> childIds = dslContext.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(PARENT_CONVERSATION_ID.eq(internalConversationId))
                .fetch(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        var conversationScope = AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                .or(childIds.isEmpty() ? org.jooq.impl.DSL.falseCondition()
                        : AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.in(childIds));
        List<TrajectoryRow> rows = dslContext.select(
                        AI_CHAT_STEP.STEP_SEQUENCE, AI_CHAT_STEP.REQUEST_ID, AI_CHAT_STEP.SOURCE,
                        AI_CHAT_STEP.MESSAGE_KIND, AI_CHAT_STEP.VISIBILITY, AI_CHAT_STEP.MESSAGE,
                        AI_CHAT_STEP.REASONING_CONTENT, AI_CHAT_STEP.MODEL_NAME,
                        AI_CHAT_STEP.REASONING_EFFORT, AI_CHAT_STEP.AGENT_RUNTIME,
                        AI_CHAT_STEP.RUNTIME_OPTIONS_JSON, AI_CHAT_STEP.TOOL_CALLS_JSON,
                        AI_CHAT_STEP.OBSERVATION_JSON, AI_CHAT_STEP.METRICS_JSON,
                        AI_CHAT_STEP.EXTRA_JSON, AI_CHAT_STEP.LLM_CALL_COUNT,
                        AI_CHAT_STEP.IS_COPIED_CONTEXT, AI_CHAT_STEP.CREATED_AT)
                .from(AI_CHAT_STEP)
                .where(conversationScope)
                .orderBy(AI_CHAT_STEP.CREATED_AT.desc(), AI_CHAT_STEP.AI_CHAT_STEP_ID.desc())
                .limit(MAX_TRAJECTORY_STEPS)
                .fetch(this::trajectoryRow);
        Collections.reverse(rows);

        List<Map<String, Object>> steps = new ArrayList<>(rows.size());
        long promptTokens = 0;
        long completionTokens = 0;
        long cachedTokens = 0;
        for (int index = 0; index < rows.size(); index++) {
            TrajectoryRow row = rows.get(index);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step_id", index + 1);
            step.put("timestamp", row.createdAt().toString());
            step.put("source", row.source());
            if (StringUtils.hasText(row.modelName()) && "agent".equals(row.source())) {
                step.put("model_name", row.modelName());
            }
            step.put("message", row.message());
            putIfPresent(step, "reasoning_content", row.reasoningContent());
            putIfPresent(step, "tool_calls", row.toolCalls());
            putIfPresent(step, "observation", row.observation());
            putIfPresent(step, "metrics", row.metrics());
            if (row.metrics() != null) {
                promptTokens += number(row.metrics().get("prompt_tokens"));
                completionTokens += number(row.metrics().get("completion_tokens"));
                cachedTokens += number(row.metrics().get("cached_tokens"));
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            if (row.extra() != null) {
                extra.putAll(row.extra());
            }
            putIfPresent(extra, "request_id", row.requestId());
            extra.put("message_kind", row.messageKind());
            extra.put("visibility", row.visibility());
            putIfPresent(extra, "reasoning_effort", row.reasoningEffort());
            putIfPresent(extra, "runtime", row.runtime());
            putIfPresent(extra, "runtime_options", row.runtimeOptions());
            step.put("extra", extra);
            putIfPresent(step, "llm_call_count", row.llmCallCount());
            putIfPresent(step, "is_copied_context", row.isCopiedContext());
            steps.add(step);
        }

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", "connectCenter-assistant");
        agent.put("version", StringUtils.hasText(agentVersion) ? agentVersion : "unknown");
        if (StringUtils.hasText(defaultModel)) {
            agent.put("model_name", defaultModel);
        }

        Map<String, Object> finalMetrics = new LinkedHashMap<>();
        finalMetrics.put("total_prompt_tokens", promptTokens);
        finalMetrics.put("total_completion_tokens", completionTokens);
        finalMetrics.put("total_cached_tokens", cachedTokens);
        finalMetrics.put("total_steps", steps.size());

        Map<String, Object> trajectory = new LinkedHashMap<>();
        trajectory.put("schema_version", "ATIF-v1.7");
        trajectory.put("session_id", conversationId);
        trajectory.put("trajectory_id", conversationId);
        trajectory.put("agent", agent);
        trajectory.put("steps", steps);
        trajectory.put("notes", "UI projection steps are retained for auditability and marked in step.extra.");
        trajectory.put("final_metrics", finalMetrics);
        long totalSteps = dslContext.fetchCount(AI_CHAT_STEP, conversationScope);
        trajectory.put("extra", Map.of("producer", "connectCenter",
                "total_steps", totalSteps, "returned_steps", rows.size(),
                "truncated", totalSteps > rows.size()));
        return trajectory;
    }

    @Override
    @Transactional
    public void markCompacted(ScoreUser requester, String conversationId) {
        setCompacted(requester, conversationId, true);
    }

    @Override
    @Transactional
    public void markExpanded(ScoreUser requester, String conversationId) {
        setCompacted(requester, conversationId, false);
    }

    private void setCompacted(ScoreUser requester, String conversationId, boolean compacted) {
        ULong internalConversationId = lockOwned(requester, conversationId);
        dslContext.update(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.COMPACTED, byteValue(compacted))
                .set(AI_CHAT_CONVERSATION.UPDATED_AT, LocalDateTime.now())
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .execute();
    }

    @Override
    @Transactional
    public int deleteExpiredConversations(Instant cutoff) {
        return dslContext.deleteFrom(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.UPDATED_AT.lt(localDateTime(cutoff))
                        .and(PARENT_CONVERSATION_ID.isNull()))
                .execute();
    }

    @Override
    @Transactional
    public int expireMutationConfirmations(Instant now) {
        return dslContext.update(AI_CHAT_MUTATION_CONFIRMATION)
                .set(AI_CHAT_MUTATION_CONFIRMATION.STATUS, "EXPIRED")
                .set(AI_CHAT_MUTATION_CONFIRMATION.EXPIRED_AT,
                        AI_CHAT_MUTATION_CONFIRMATION.EXPIRES_AT)
                .setNull(AI_CHAT_MUTATION_CONFIRMATION.GRANT_DIGEST)
                .where(AI_CHAT_MUTATION_CONFIRMATION.STATUS.in("REQUESTED", "APPROVED")
                        .and(AI_CHAT_MUTATION_CONFIRMATION.EXPIRES_AT.le(localDateTime(now))))
                .execute();
    }

    @Override
    @Transactional
    public boolean delete(ScoreUser requester, String conversationId) {
        requireOwned(requester, conversationId);
        return dslContext.deleteFrom(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId(requester))))
                .execute() > 0;
    }

    @Override
    public List<String> findConversationIds() {
        return dslContext.selectDistinct(AI_CHAT_CONVERSATION.GUID)
                .from(AI_CHAT_MEMORY)
                .join(AI_CHAT_CONVERSATION).on(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_MEMORY.AI_CHAT_CONVERSATION_ID))
                .orderBy(AI_CHAT_CONVERSATION.GUID)
                .limit(1000)
                .fetch(AI_CHAT_CONVERSATION.GUID);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        requireConversationId(conversationId);
        return dslContext.select(AI_CHAT_MEMORY.MESSAGE_TYPE, AI_CHAT_MEMORY.CONTENT,
                        AI_CHAT_MEMORY.METADATA_JSON)
                .from(AI_CHAT_MEMORY)
                .join(AI_CHAT_CONVERSATION).on(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_MEMORY.AI_CHAT_CONVERSATION_ID))
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId))
                .orderBy(AI_CHAT_MEMORY.MEMORY_SEQUENCE)
                .fetch(this::memoryMessage);
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        requireConversationId(conversationId);
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("messages must not contain null elements");
        }
        ULong internalConversationId = internalConversationId(conversationId);
        dslContext.deleteFrom(AI_CHAT_MEMORY)
                .where(AI_CHAT_MEMORY.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .execute();
        Instant now = Instant.now();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            AiChatMemoryRecord record = new AiChatMemoryRecord();
            record.setAiChatConversationId(internalConversationId);
            record.setMemorySequence((long) index);
            record.setMessageType(message.getMessageType().name());
            record.setContent(Objects.requireNonNullElse(message.getText(), ""));
            record.setMetadataJson(memoryMetadata(message));
            record.setCreatedAt(localDateTime(now));
            dslContext.insertInto(AI_CHAT_MEMORY).set(record).execute();
        }
    }

    @Override
    @Transactional
    public void deleteByConversationId(String conversationId) {
        requireConversationId(conversationId);
        dslContext.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId))
                .fetchOptional(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .ifPresent(internalConversationId -> dslContext.deleteFrom(AI_CHAT_MEMORY)
                        .where(AI_CHAT_MEMORY.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                        .execute());
    }

    private ChatHistoryMessage historyMessage(Record record) {
        String kind = record.get(AI_CHAT_STEP.MESSAGE_KIND);
        String reasoning = record.get(AI_CHAT_STEP.REASONING_CONTENT);
        String content = record.get(AI_CHAT_STEP.MESSAGE);
        Map<String, Object> storedExtra = readMap(record.get(AI_CHAT_STEP.EXTRA_JSON));
        Map<String, Object> extra = storedExtra != null ? storedExtra : Map.of();
        String role = switch (kind) {
            case "assistant", "user", "error", "progress", "tool_call", "guide" -> kind;
            case "tool_call_update" -> "tool_call";
            case "agent_lifecycle" -> "agent_event";
            default -> "debug";
        };
        if ("debug".equals(role) && StringUtils.hasText(reasoning)) {
            content = "Reasoning: " + reasoning;
        }
        String requestId = record.get(AI_CHAT_STEP.REQUEST_ID);
        String toolCallId = string(extra, "tool_call_id");
        String toolName = string(extra, "tool_name");
        String toolStatus = string(extra, "tool_status");
        Long toolCallSequence = longValue(extra.get("tool_call_sequence"));
        Map<String, Object> metadata = new LinkedHashMap<>(extra);
        putIfPresent(metadata, "toolName", toolName);
        putIfPresent(metadata, "toolCallSeq", toolCallSequence);
        return new ChatHistoryMessage(record.get(AI_CHAT_STEP.STEP_SEQUENCE).intValue(), role,
                Objects.requireNonNullElse(content, ""), requestId, requestId,
                toolCallId != null ? requestId : null, toolCallId, toolCallSequence,
                "agent_event".equals(role) ? string(extra, "lifecycle_subtype") : toolStatus,
                record.get(AI_CHAT_STEP.VISIBILITY), metadata);
    }

    private ChatHistoryMessage withIndex(ChatHistoryMessage message, int index) {
        return new ChatHistoryMessage(index, message.role(), message.content(), message.requestId(),
                message.turnId(), message.groupId(), message.toolCallId(), message.toolCallSequence(),
                message.subtype(), message.visibility(), message.metadata());
    }

    private TrajectoryRow trajectoryRow(Record record) {
        LocalDateTime createdAt = record.get(AI_CHAT_STEP.CREATED_AT);
        return new TrajectoryRow(record.get(AI_CHAT_STEP.STEP_SEQUENCE),
                record.get(AI_CHAT_STEP.REQUEST_ID), record.get(AI_CHAT_STEP.SOURCE),
                record.get(AI_CHAT_STEP.MESSAGE_KIND), record.get(AI_CHAT_STEP.VISIBILITY),
                record.get(AI_CHAT_STEP.MESSAGE), record.get(AI_CHAT_STEP.REASONING_CONTENT),
                record.get(AI_CHAT_STEP.MODEL_NAME), record.get(AI_CHAT_STEP.REASONING_EFFORT),
                record.get(AI_CHAT_STEP.AGENT_RUNTIME),
                mapOrEmpty(record.get(AI_CHAT_STEP.RUNTIME_OPTIONS_JSON)),
                readList(record.get(AI_CHAT_STEP.TOOL_CALLS_JSON)),
                readMap(record.get(AI_CHAT_STEP.OBSERVATION_JSON)),
                readMap(record.get(AI_CHAT_STEP.METRICS_JSON)),
                readMap(record.get(AI_CHAT_STEP.EXTRA_JSON)),
                record.get(AI_CHAT_STEP.LLM_CALL_COUNT),
                byteBoolean(record.get(AI_CHAT_STEP.IS_COPIED_CONTEXT)),
                createdAt != null ? instant(createdAt) : Instant.EPOCH);
    }

    private Message memoryMessage(Record record) {
        MessageType type = MessageType.valueOf(record.get(AI_CHAT_MEMORY.MESSAGE_TYPE));
        String content = record.get(AI_CHAT_MEMORY.CONTENT);
        Map<String, Object> metadata = readMap(record.get(AI_CHAT_MEMORY.METADATA_JSON));
        metadata = metadata != null ? metadata : Map.of();
        return switch (type) {
            case USER -> UserMessage.builder().text(content).metadata(metadata).build();
            case ASSISTANT -> AssistantMessage.builder().content(content).properties(metadata)
                    .toolCalls(assistantToolCalls(metadata)).build();
            case SYSTEM -> SystemMessage.builder().text(content).metadata(metadata).build();
            case TOOL -> ToolResponseMessage.builder().responses(toolResponses(metadata)).metadata(metadata).build();
        };
    }

    private String memoryMetadata(Message message) {
        Map<String, Object> metadata = new LinkedHashMap<>(message.getMetadata());
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            metadata.put("score_tool_calls", assistant.getToolCalls());
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            metadata.put("score_tool_responses", toolResponse.getResponses());
        }
        return jsonOrNull(metadata);
    }

    private List<ToolResponseMessage.ToolResponse> toolResponses(Map<String, Object> metadata) {
        Object raw = metadata.get("score_tool_responses");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                responses.add(new ToolResponseMessage.ToolResponse(
                        Objects.toString(map.get("id"), ""), Objects.toString(map.get("name"), ""),
                        Objects.toString(map.get("responseData"), "")));
            }
        }
        return responses;
    }

    private List<AssistantMessage.ToolCall> assistantToolCalls(Map<String, Object> metadata) {
        Object raw = metadata.get("score_tool_calls");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                calls.add(new AssistantMessage.ToolCall(
                        Objects.toString(map.get("id"), ""), Objects.toString(map.get("type"), "function"),
                        Objects.toString(map.get("name"), ""), Objects.toString(map.get("arguments"), "{}")));
            }
        }
        return calls;
    }

    private void requireOwned(ScoreUser requester, String conversationId) {
        boolean exists = dslContext.fetchExists(AI_CHAT_CONVERSATION,
                AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId(requester))));
        if (!exists) {
            throw new AccessDeniedException("AI conversation does not exist or is not owned by the signed-in user.");
        }
    }

    private ULong ownedId(ScoreUser requester, String conversationId) {
        return ownedIdQuery(requester, conversationId, false);
    }

    private ULong lockOwned(ScoreUser requester, String conversationId) {
        return ownedIdQuery(requester, conversationId, true);
    }

    private ULong ownedIdQuery(ScoreUser requester, String conversationId, boolean forUpdate) {
        var query = dslContext.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId(requester))));
        ULong result = forUpdate
                ? query.forUpdate().fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                : query.fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        if (result == null) {
            throw new AccessDeniedException(
                    "AI conversation does not exist or is not owned by the signed-in user.");
        }
        return result;
    }

    private AiChatConversationSettings latestSettings(ULong internalConversationId) {
        return dslContext.select(AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.REASONING_EFFORT,
                        AI_CHAT_STEP.AGENT_RUNTIME, AI_CHAT_STEP.RUNTIME_OPTIONS_JSON)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("settings_change"))
                        .and(AI_CHAT_STEP.MODEL_NAME.isNotNull())
                        .and(AI_CHAT_STEP.REASONING_EFFORT.isNotNull())
                        .and(AI_CHAT_STEP.AGENT_RUNTIME.isNotNull()))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(record -> new AiChatConversationSettings(
                        record.get(AI_CHAT_STEP.MODEL_NAME),
                        record.get(AI_CHAT_STEP.REASONING_EFFORT),
                        record.get(AI_CHAT_STEP.AGENT_RUNTIME),
                        mapOrEmpty(record.get(AI_CHAT_STEP.RUNTIME_OPTIONS_JSON))))
                .orElseThrow(() -> new IllegalStateException(
                        "AI conversation has no settings snapshot."));
    }

    private ULong internalConversationId(String conversationId) {
        return dslContext.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId))
                .fetchOptional(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .orElseThrow(() -> new IllegalArgumentException("AI conversation does not exist."));
    }

    private ULong userId(ScoreUser requester) {
        return ULong.valueOf(requester.userId().value());
    }

    private String title(String prompt) {
        String value = StringUtils.hasText(prompt) ? prompt.strip().replaceAll("\\s+", " ") : "New conversation";
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
    }

    private void requireConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId must not be empty");
        }
    }

    private String jsonOrNull(Object value) {
        if (value == null || value instanceof Map<?, ?> map && map.isEmpty()
                || value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize assistant trajectory data.", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored assistant trajectory data is invalid.", exception);
        }
    }

    private Map<String, Object> mapOrEmpty(String json) {
        Map<String, Object> value = readMap(json);
        return value != null ? Map.copyOf(value) : Map.of();
    }

    private List<Map<String, Object>> readList(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LIST_OF_MAPS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored assistant trajectory data is invalid.", exception);
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private Instant instant(LocalDateTime value) {
        return value != null ? value.atZone(ZoneId.systemDefault()).toInstant() : null;
    }

    private LocalDateTime localDateTime(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String string(Map<String, Object> map, String key) {
        Object value = map != null ? map.get(key) : null;
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Byte byteValue(Boolean value) {
        return value == null ? null : byteValue(value.booleanValue());
    }

    private byte byteValue(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    private Boolean byteBoolean(Byte value) {
        return value != null ? value != 0 : null;
    }

    private record Header(String title, Instant updatedAt) {}

    private record TrajectoryRow(long sequence, String requestId, String source, String messageKind,
                                 String visibility, String message, String reasoningContent,
                                 String modelName, String reasoningEffort, String runtime,
                                 Map<String, Object> runtimeOptions,
                                 List<Map<String, Object>> toolCalls,
                                 Map<String, Object> observation, Map<String, Object> metrics,
                                 Map<String, Object> extra, Integer llmCallCount,
                                 Boolean isCopiedContext, Instant createdAt) {}
}

package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
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
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiChatConversationRecord;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiChatStepRecord;
import org.springframework.security.access.AccessDeniedException;
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
import static org.jooq.impl.DSL.max;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatStep.AI_CHAT_STEP;

/**
 * Requester-scoped repository for conversation metadata and persisted step data.
 * Spring AI model memory is persisted separately by
 * {@link org.oagi.score.gateway.http.api.ai_management.memory.ScoreChatMemoryRepository}.
 */
public class JooqAiChatConversationRepository extends JooqBaseRepository
        implements AiChatConversationRepository {

    private final AiChatJsonSerializer serializer;

    /**
     * Creates the conversation repository.
     *
     * @param dslContext context used to execute generated-model queries and commands
     * @param requester signed-in owner used to scope conversation access
     * @param repositoryFactory factory for requester-scoped repository dependencies
     * @param serializer serializer for JSON-backed AI chat data
     */
    public JooqAiChatConversationRepository(DSLContext dslContext, ScoreUser requester,
                                            RepositoryFactory repositoryFactory,
                                            AiChatJsonSerializer serializer) {
        super(dslContext, requester, repositoryFactory);
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
    }

    @Override
    @Transactional
    public String open(String requestedConversationId, String firstPrompt) {
        if (StringUtils.hasText(requestedConversationId)) {
            ULong internalConversationId = lockOwned(requestedConversationId);
            dslContext().update(AI_CHAT_CONVERSATION)
                    .set(AI_CHAT_CONVERSATION.UPDATED_AT, LocalDateTime.now())
                    .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                    .execute();
            return requestedConversationId;
        }
        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        AiChatConversationRecord record = new AiChatConversationRecord();
        record.setGuid(conversationId);
        record.setAppUserId(userId());
        record.setTitle(title(firstPrompt));
        record.setCompacted((byte) 0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        dslContext().insertInto(AI_CHAT_CONVERSATION).set(record).execute();
        return conversationId;
    }

    @Override
    @Transactional
    public String openChild(String parentConversationId, String parentRequestId,
                            AiChatConversationKind kind,
                            String workerId, String firstPrompt) {
        if (kind == null || !kind.isChild() || !StringUtils.hasText(parentRequestId)
                || !StringUtils.hasText(workerId)) {
            throw new IllegalArgumentException("Child execution conversation identity is incomplete.");
        }
        ULong parentId = lockOwned(parentConversationId);
        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        dslContext().insertInto(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.GUID, conversationId)
                .set(AI_CHAT_CONVERSATION.APP_USER_ID, userId())
                .set(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID, parentId)
                .set(AI_CHAT_CONVERSATION.CONVERSATION_KIND, kind.name())
                .set(AI_CHAT_CONVERSATION.AGENT_ID, workerId.strip())
                .set(AI_CHAT_CONVERSATION.PARENT_REQUEST_ID, parentRequestId.strip())
                .set(AI_CHAT_CONVERSATION.TITLE, title(firstPrompt))
                .set(AI_CHAT_CONVERSATION.COMPACTED, (byte) 0)
                .set(AI_CHAT_CONVERSATION.CREATED_AT, now)
                .set(AI_CHAT_CONVERSATION.UPDATED_AT, now)
                .execute();
        return conversationId;
    }

    @Override
    @Transactional(readOnly = true)
    public String modelName(String conversationId) {
        return settings(conversationId).modelName();
    }

    @Override
    @Transactional(readOnly = true)
    public AiChatConversationSettings settings(String conversationId) {
        return latestSettings(ownedId(conversationId));
    }

    @Override
    @Transactional
    public AiChatConversationSettings settingsForUpdate(String conversationId) {
        return latestSettings(lockOwned(conversationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> activeWorkflow(String conversationId) {
        ULong internalConversationId = ownedId(conversationId);
        return dslContext().select(AI_CHAT_STEP.EXTRA_JSON)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("workflow_preference")))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(AI_CHAT_STEP.EXTRA_JSON)
                .flatMap(value -> Optional.ofNullable(
                        serializer.deserializeMapOrEmpty(value).get("activeWorkflow")))
                .map(Object::toString)
                .filter(StringUtils::hasText);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiChatLatestUsage> latestUsage(String conversationId) {
        ULong internalConversationId = ownedId(conversationId);
        return dslContext().select(AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.METRICS_JSON,
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
            Map<String, Object> metrics = serializer.deserializeMapOrEmpty(
                    record.get(AI_CHAT_STEP.METRICS_JSON));
            long inputTokens = number(metrics.get("context_input_tokens"));
            if (inputTokens <= 0) inputTokens = number(metrics.get("prompt_tokens"));
            return new AiChatLatestUsage(record.get(AI_CHAT_STEP.MODEL_NAME), inputTokens,
                    Boolean.TRUE.equals(metrics.get("context_estimated")),
                    instant(record.get(AI_CHAT_STEP.CREATED_AT)));
        });
    }

    @Override
    @Transactional
    public AiChatStoredStep append(String conversationId, AiChatTrajectoryStep step) {
        ULong internalConversationId = lockOwned(conversationId);
        Long nextSequence = dslContext().select(
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
        stepRecord.setRuntimeOptionsJson(serializer.serialize(step.runtimeOptions()));
        stepRecord.setToolCallsJson(serializer.serialize(step.toolCalls()));
        stepRecord.setObservationJson(serializer.serialize(step.observation()));
        stepRecord.setMetricsJson(serializer.serialize(step.metrics()));
        stepRecord.setExtraJson(serializer.serialize(step.extra()));
        stepRecord.setLlmCallCount(step.llmCallCount());
        stepRecord.setIsCopiedContext(byteValue(step.isCopiedContext()));
        stepRecord.setCreatedAt(localDateTime(createdAt));
        ULong id = dslContext().insertInto(AI_CHAT_STEP)
                .set(stepRecord)
                .returningResult(AI_CHAT_STEP.AI_CHAT_STEP_ID)
                .fetchSingle(AI_CHAT_STEP.AI_CHAT_STEP_ID);
        dslContext().update(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.UPDATED_AT, localDateTime(createdAt))
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .execute();
        return new AiChatStoredStep(id.longValue(), sequence, createdAt);
    }

    @Override
    @Transactional
    public void updateObservation(String conversationId, long stepId,
                                  Map<String, Object> observation) {
        ULong internalConversationId = lockOwned(conversationId);
        int updated = dslContext().update(AI_CHAT_STEP)
                .set(AI_CHAT_STEP.OBSERVATION_JSON, serializer.serialize(observation))
                .where(AI_CHAT_STEP.AI_CHAT_STEP_ID.eq(ULong.valueOf(stepId))
                        .and(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)))
                .execute();
        if (updated != 1) {
            throw new IllegalArgumentException("The trajectory step no longer exists.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatConversationSummary> list() {
        var messageCount = count(AI_CHAT_STEP.AI_CHAT_STEP_ID)
                .filterWhere(AI_CHAT_STEP.MESSAGE_KIND.in("user", "assistant", "error"))
                .as("message_count");
        return dslContext().select(AI_CHAT_CONVERSATION.GUID, AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.COMPACTED, AI_CHAT_CONVERSATION.CREATED_AT,
                        AI_CHAT_CONVERSATION.UPDATED_AT, messageCount)
                .from(AI_CHAT_CONVERSATION)
                .leftJoin(AI_CHAT_STEP).on(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID))
                .where(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId())
                        .and(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID.isNull()))
                .groupBy(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_CONVERSATION.GUID, AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.COMPACTED, AI_CHAT_CONVERSATION.CREATED_AT,
                        AI_CHAT_CONVERSATION.UPDATED_AT)
                .orderBy(AI_CHAT_CONVERSATION.UPDATED_AT.desc())
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
    public ChatConversationDetails get(String conversationId) {
        ULong internalConversationId = ownedId(conversationId);
        Header header = dslContext().select(AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.UPDATED_AT)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .fetchSingle(record -> new Header(record.get(AI_CHAT_CONVERSATION.TITLE),
                        instant(record.get(AI_CHAT_CONVERSATION.UPDATED_AT))));
        AiChatConversationSettings settings = latestSettings(internalConversationId);
        String permissionMode = latestPermissionMode(internalConversationId);
        List<ULong> childIds = dslContext().select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .fetch(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        var visibleChildKinds = AI_CHAT_STEP.MESSAGE_KIND.in(
                "agent_lifecycle", "tool_call", "tool_call_update", "guide");
        List<ChatHistoryMessage> messages = dslContext().select(
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
                .fetch(this::historyMessage);
        Collections.reverse(messages);
        List<ChatHistoryMessage> indexedMessages = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            indexedMessages.add(withIndex(messages.get(index), index));
        }
        return new ChatConversationDetails(conversationId, header.title(), settings.modelName(),
                settings.reasoningEffort(), settings.runtime(), settings.runtimeOptions(), header.updatedAt(),
                indexedMessages, List.<ChatContextMessage>of(), null, permissionMode);
    }

    @Override
    @Transactional(readOnly = true)
    public AiChatTrajectoryData getTrajectoryData(String conversationId) {
        ULong internalConversationId = ownedId(conversationId);
        List<ChildTrajectoryHeader> childHeaders = dslContext().select(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_CONVERSATION.GUID, AI_CHAT_CONVERSATION.CONVERSATION_KIND,
                        AI_CHAT_CONVERSATION.AGENT_ID, AI_CHAT_CONVERSATION.PARENT_REQUEST_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .fetch(record -> new ChildTrajectoryHeader(
                        record.get(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID),
                        record.get(AI_CHAT_CONVERSATION.GUID),
                        record.get(AI_CHAT_CONVERSATION.CONVERSATION_KIND),
                        record.get(AI_CHAT_CONVERSATION.AGENT_ID),
                        record.get(AI_CHAT_CONVERSATION.PARENT_REQUEST_ID)));
        List<ULong> childIds = childHeaders.stream()
                .map(ChildTrajectoryHeader::internalId).toList();
        var conversationScope = AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                .or(childIds.isEmpty() ? org.jooq.impl.DSL.falseCondition()
                        : AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.in(childIds));
        List<TrajectoryRow> rows = dslContext().select(
                        AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID,
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
                .fetch(this::trajectoryRow);
        Collections.reverse(rows);

        List<AiChatTrajectoryData.ChildTrajectory> childTrajectories = new ArrayList<>();
        for (ChildTrajectoryHeader child : childHeaders) {
            List<AiChatTrajectoryData.Step> childRows = rows.stream()
                    .filter(row -> child.internalId().equals(row.conversationId()))
                    .map(TrajectoryRow::step)
                    .toList();
            if (childRows.isEmpty()) {
                continue;
            }
            childTrajectories.add(new AiChatTrajectoryData.ChildTrajectory(
                    child.guid(), child.conversationKind(), child.agentId(),
                    child.parentRequestId(), childRows));
        }

        List<AiChatTrajectoryData.Step> rootRows = rows.stream()
                .filter(row -> internalConversationId.equals(row.conversationId()))
                .map(TrajectoryRow::step)
                .toList();
        return new AiChatTrajectoryData(conversationId, rows.size(), false,
                rootRows, List.copyOf(childTrajectories));
    }

    @Override
    @Transactional
    public void markCompacted(String conversationId) {
        setCompacted(conversationId, true);
    }

    @Override
    @Transactional
    public void markExpanded(String conversationId) {
        setCompacted(conversationId, false);
    }

    private void setCompacted(String conversationId, boolean compacted) {
        ULong internalConversationId = lockOwned(conversationId);
        dslContext().update(AI_CHAT_CONVERSATION)
                .set(AI_CHAT_CONVERSATION.COMPACTED, byteValue(compacted))
                .set(AI_CHAT_CONVERSATION.UPDATED_AT, LocalDateTime.now())
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(internalConversationId))
                .execute();
    }

    @Override
    @Transactional
    public boolean delete(String conversationId) {
        requireOwned(conversationId);
        return dslContext().deleteFrom(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId())))
                .execute() > 0;
    }

    private ChatHistoryMessage historyMessage(Record record) {
        String kind = record.get(AI_CHAT_STEP.MESSAGE_KIND);
        String reasoning = record.get(AI_CHAT_STEP.REASONING_CONTENT);
        String content = record.get(AI_CHAT_STEP.MESSAGE);
        Map<String, Object> storedExtra = serializer.deserializeMap(
                record.get(AI_CHAT_STEP.EXTRA_JSON));
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
                AiChatTrajectoryStep.normalizeVisibility(
                        record.get(AI_CHAT_STEP.VISIBILITY)), metadata);
    }

    private ChatHistoryMessage withIndex(ChatHistoryMessage message, int index) {
        return new ChatHistoryMessage(index, message.role(), message.content(), message.requestId(),
                message.turnId(), message.groupId(), message.toolCallId(), message.toolCallSequence(),
                message.subtype(), message.visibility(), message.metadata());
    }

    private TrajectoryRow trajectoryRow(Record record) {
        LocalDateTime createdAt = record.get(AI_CHAT_STEP.CREATED_AT);
        String messageKind = record.get(AI_CHAT_STEP.MESSAGE_KIND);
        String modelName = record.get(AI_CHAT_STEP.MODEL_NAME);
        String reasoningEffort = record.get(AI_CHAT_STEP.REASONING_EFFORT);
        String agentRuntime = record.get(AI_CHAT_STEP.AGENT_RUNTIME);
        validateStoredSettingsChange(messageKind, modelName, reasoningEffort, agentRuntime);
        return new TrajectoryRow(record.get(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID),
                record.get(AI_CHAT_STEP.STEP_SEQUENCE),
                record.get(AI_CHAT_STEP.REQUEST_ID),
                AiChatTrajectoryStep.normalizeSource(record.get(AI_CHAT_STEP.SOURCE)),
                messageKind,
                AiChatTrajectoryStep.normalizeVisibility(record.get(AI_CHAT_STEP.VISIBILITY)),
                record.get(AI_CHAT_STEP.MESSAGE), record.get(AI_CHAT_STEP.REASONING_CONTENT),
                modelName, reasoningEffort, agentRuntime,
                serializer.deserializeMapOrEmpty(record.get(AI_CHAT_STEP.RUNTIME_OPTIONS_JSON)),
                serializer.deserializeListOfMaps(record.get(AI_CHAT_STEP.TOOL_CALLS_JSON)),
                serializer.deserializeMap(record.get(AI_CHAT_STEP.OBSERVATION_JSON)),
                serializer.deserializeMap(record.get(AI_CHAT_STEP.METRICS_JSON)),
                serializer.deserializeMap(record.get(AI_CHAT_STEP.EXTRA_JSON)),
                record.get(AI_CHAT_STEP.LLM_CALL_COUNT),
                byteBoolean(record.get(AI_CHAT_STEP.IS_COPIED_CONTEXT)),
                createdAt != null ? instant(createdAt) : Instant.EPOCH);
    }

    static void validateStoredSettingsChange(String messageKind, String modelName,
                                             String reasoningEffort, String agentRuntime) {
        if ("settings_change".equals(messageKind)
                && (modelName == null || reasoningEffort == null || agentRuntime == null)) {
            throw new IllegalStateException(
                    "Stored AI chat settings_change step is incomplete: model_name, "
                            + "reasoning_effort, and agent_runtime are required and have no safe defaults.");
        }
    }

    private void requireOwned(String conversationId) {
        boolean exists = dslContext().fetchExists(AI_CHAT_CONVERSATION,
                AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId())));
        if (!exists) {
            throw new AccessDeniedException("AI conversation does not exist or is not owned by the signed-in user.");
        }
    }

    private ULong ownedId(String conversationId) {
        return ownedIdQuery(conversationId, false);
    }

    private ULong lockOwned(String conversationId) {
        return ownedIdQuery(conversationId, true);
    }

    private ULong ownedIdQuery(String conversationId, boolean forUpdate) {
        var query = dslContext().select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(userId())));
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
        return dslContext().select(AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.REASONING_EFFORT,
                        AI_CHAT_STEP.AGENT_RUNTIME, AI_CHAT_STEP.RUNTIME_OPTIONS_JSON)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("settings_change")))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(record -> {
                    String modelName = record.get(AI_CHAT_STEP.MODEL_NAME);
                    String reasoningEffort = record.get(AI_CHAT_STEP.REASONING_EFFORT);
                    String agentRuntime = record.get(AI_CHAT_STEP.AGENT_RUNTIME);
                    validateStoredSettingsChange(
                            "settings_change", modelName, reasoningEffort, agentRuntime);
                    return new AiChatConversationSettings(modelName, reasoningEffort, agentRuntime,
                            serializer.deserializeMapOrEmpty(
                                    record.get(AI_CHAT_STEP.RUNTIME_OPTIONS_JSON)));
                })
                .orElseThrow(() -> new IllegalStateException(
                        "AI conversation has no settings snapshot."));
    }

    private String latestPermissionMode(ULong internalConversationId) {
        return dslContext().select(AI_CHAT_STEP.EXTRA_JSON)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.eq(internalConversationId)
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("user")))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(record -> string(
                        serializer.deserializeMapOrEmpty(record.get(AI_CHAT_STEP.EXTRA_JSON)),
                        "permission_mode"))
                .filter(mode -> "auto".equals(mode) || "full_access".equals(mode))
                .orElse("ask");
    }

    private ULong userId() {
        return ULong.valueOf(requester().userId().value());
    }

    private String title(String prompt) {
        String value = StringUtils.hasText(prompt) ? prompt.strip().replaceAll("\\s+", " ") : "New conversation";
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
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

    private record ChildTrajectoryHeader(ULong internalId, String guid, String conversationKind,
                                         String agentId, String parentRequestId) {}

    private record TrajectoryRow(ULong conversationId, long sequence,
                                 String requestId, String source, String messageKind,
                                 String visibility, String message, String reasoningContent,
                                 String modelName, String reasoningEffort, String runtime,
                                 Map<String, Object> runtimeOptions,
                                 List<Map<String, Object>> toolCalls,
                                 Map<String, Object> observation, Map<String, Object> metrics,
                                 Map<String, Object> extra, Integer llmCallCount,
                                 Boolean isCopiedContext, Instant createdAt) {

        private AiChatTrajectoryData.Step step() {
            return new AiChatTrajectoryData.Step(sequence, requestId, source, messageKind,
                    visibility, message, reasoningContent, modelName, reasoningEffort,
                    runtime, runtimeOptions, toolCalls, observation, metrics, extra,
                    llmCallCount, isCopiedContext, createdAt);
        }
    }
}

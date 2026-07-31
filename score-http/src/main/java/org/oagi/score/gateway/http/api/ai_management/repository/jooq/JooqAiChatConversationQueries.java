package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatContextMessage;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationSummary;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatHistoryMessage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationSettings;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatLatestUsage;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.jooq.impl.DSL.count;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatStep.AI_CHAT_STEP;

/** Owns requester-scoped conversation, history, settings, usage, and trajectory reads. */
final class JooqAiChatConversationQueries {

    private final DSLContext dslContext;
    private final JooqAiChatConversationAccess access;
    private final AiChatJsonSerializer serializer;

    JooqAiChatConversationQueries(DSLContext dslContext,
                                  JooqAiChatConversationAccess access,
                                  AiChatJsonSerializer serializer) {
        this.dslContext = dslContext;
        this.access = access;
        this.serializer = serializer;
    }

    String modelName(String conversationId) {
        return settings(conversationId).modelName();
    }

    AiChatConversationSettings settings(String conversationId) {
        return latestSettings(access.ownedId(conversationId));
    }

    AiChatConversationSettings latestSettings(AiChatConversationId internalConversationId) {
        return dslContext.select(AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.REASONING_EFFORT)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId))
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("settings_change")))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(record -> {
                    String modelName = record.get(AI_CHAT_STEP.MODEL_NAME);
                    String reasoningEffort = record.get(AI_CHAT_STEP.REASONING_EFFORT);
                    validateStoredSettingsChange("settings_change", modelName, reasoningEffort);
                    return new AiChatConversationSettings(modelName, reasoningEffort);
                })
                .orElseThrow(() -> new IllegalStateException(
                        "AI conversation has no settings snapshot."));
    }

    Optional<String> activeWorkflow(String conversationId) {
        return latestActiveWorkflow(access.ownedId(conversationId));
    }

    private Optional<String> latestActiveWorkflow(AiChatConversationId internalConversationId) {
        return dslContext.select(AI_CHAT_STEP.EXTRA_JSON)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId))
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("workflow_preference")))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(AI_CHAT_STEP.EXTRA_JSON)
                .flatMap(value -> Optional.ofNullable(
                        serializer.deserializeMapOrEmpty(value).get("activeWorkflow")))
                .map(Object::toString)
                .filter(StringUtils::hasText);
    }

    Optional<AiChatLatestUsage> latestUsage(String conversationId) {
        AiChatConversationId internalConversationId = access.ownedId(conversationId);
        return dslContext.select(AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.METRICS_JSON,
                        AI_CHAT_STEP.CREATED_AT)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId))
                        .and(AI_CHAT_STEP.METRICS_JSON.isNotNull())
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

    List<ChatConversationSummary> list() {
        var messageCount = count(AI_CHAT_STEP.AI_CHAT_STEP_ID)
                .filterWhere(AI_CHAT_STEP.MESSAGE_KIND.in("user", "assistant", "error"))
                .as("message_count");
        return dslContext.select(AI_CHAT_CONVERSATION.GUID, AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.COMPACTED, AI_CHAT_CONVERSATION.CREATED_AT,
                        AI_CHAT_CONVERSATION.UPDATED_AT, messageCount)
                .from(AI_CHAT_CONVERSATION)
                .leftJoin(AI_CHAT_STEP).on(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID))
                .where(AI_CHAT_CONVERSATION.APP_USER_ID.eq(access.valueOf(access.userId()))
                        .and(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID.isNull()))
                .groupBy(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_CONVERSATION.GUID, AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.COMPACTED, AI_CHAT_CONVERSATION.CREATED_AT,
                        AI_CHAT_CONVERSATION.UPDATED_AT)
                .orderBy(AI_CHAT_CONVERSATION.UPDATED_AT.desc())
                .fetch(record -> new ChatConversationSummary(
                        record.get(AI_CHAT_CONVERSATION.GUID),
                        record.get(AI_CHAT_CONVERSATION.TITLE), record.get(messageCount),
                        Boolean.TRUE.equals(byteBoolean(record.get(AI_CHAT_CONVERSATION.COMPACTED))),
                        instant(record.get(AI_CHAT_CONVERSATION.CREATED_AT)),
                        instant(record.get(AI_CHAT_CONVERSATION.UPDATED_AT))));
    }

    ChatConversationDetails get(String conversationId) {
        AiChatConversationId internalConversationId = access.ownedId(conversationId);
        Header header = dslContext.select(AI_CHAT_CONVERSATION.TITLE,
                        AI_CHAT_CONVERSATION.UPDATED_AT)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId)))
                .fetchSingle(record -> new Header(record.get(AI_CHAT_CONVERSATION.TITLE),
                        instant(record.get(AI_CHAT_CONVERSATION.UPDATED_AT))));
        AiChatConversationSettings settings = latestSettings(internalConversationId);
        String permissionMode = latestPermissionMode(internalConversationId);
        List<AiChatConversationId> childIds = dslContext
                .select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId)))
                .fetch(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID).stream()
                .map(id -> new AiChatConversationId(id.toBigInteger()))
                .toList();
        var visibleChildKinds = AI_CHAT_STEP.MESSAGE_KIND.in(
                "agent_lifecycle", "tool_call", "tool_call_update", "guide",
                "provider_error", "provider_retry");
        List<ChatHistoryMessage> messages = dslContext.select(
                        AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID, AI_CHAT_STEP.STEP_SEQUENCE,
                        AI_CHAT_STEP.REQUEST_ID, AI_CHAT_STEP.MESSAGE_KIND, AI_CHAT_STEP.VISIBILITY,
                        AI_CHAT_STEP.MESSAGE, AI_CHAT_STEP.REASONING_CONTENT,
                        AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.TOOL_CALLS_JSON,
                        AI_CHAT_STEP.OBSERVATION_JSON, AI_CHAT_STEP.EXTRA_JSON,
                        AI_CHAT_STEP.CREATED_AT)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId))
                        .or(childIds.isEmpty() ? org.jooq.impl.DSL.falseCondition()
                                : AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.in(
                                                childIds.stream().map(access::valueOf).toList())
                                        .and(visibleChildKinds)))
                .orderBy(AI_CHAT_STEP.CREATED_AT.desc(), AI_CHAT_STEP.AI_CHAT_STEP_ID.desc())
                .fetch(this::historyMessage);
        Collections.reverse(messages);
        messages = coalesceFinalWorkflowResult(messages);
        List<ChatHistoryMessage> indexedMessages = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            indexedMessages.add(withIndex(messages.get(index), index));
        }
        return new ChatConversationDetails(conversationId, header.title(), settings.modelName(),
                settings.reasoningEffort(), header.updatedAt(), indexedMessages,
                List.<ChatContextMessage>of(), null, permissionMode,
                latestActiveWorkflow(internalConversationId).orElse(null));
    }

    AiChatTrajectoryData getTrajectoryData(String conversationId) {
        AiChatConversationId internalConversationId = access.ownedId(conversationId);
        List<ChildTrajectoryHeader> childHeaders = dslContext.select(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_CONVERSATION.GUID, AI_CHAT_CONVERSATION.CONVERSATION_KIND,
                        AI_CHAT_CONVERSATION.AGENT_ID, AI_CHAT_CONVERSATION.PARENT_REQUEST_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId)))
                .fetch(record -> new ChildTrajectoryHeader(
                        new AiChatConversationId(record.get(
                                AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID).toBigInteger()),
                        record.get(AI_CHAT_CONVERSATION.GUID),
                        record.get(AI_CHAT_CONVERSATION.CONVERSATION_KIND),
                        record.get(AI_CHAT_CONVERSATION.AGENT_ID),
                        record.get(AI_CHAT_CONVERSATION.PARENT_REQUEST_ID)));
        List<AiChatConversationId> childIds = childHeaders.stream()
                .map(ChildTrajectoryHeader::internalId).toList();
        var conversationScope = AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                .eq(access.valueOf(internalConversationId))
                .or(childIds.isEmpty() ? org.jooq.impl.DSL.falseCondition()
                        : AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID.in(
                                childIds.stream().map(access::valueOf).toList()));
        List<TrajectoryRow> rows = dslContext.select(
                        AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_STEP.STEP_SEQUENCE, AI_CHAT_STEP.REQUEST_ID, AI_CHAT_STEP.SOURCE,
                        AI_CHAT_STEP.MESSAGE_KIND, AI_CHAT_STEP.VISIBILITY, AI_CHAT_STEP.MESSAGE,
                        AI_CHAT_STEP.REASONING_CONTENT, AI_CHAT_STEP.MODEL_NAME,
                        AI_CHAT_STEP.REASONING_EFFORT, AI_CHAT_STEP.TOOL_CALLS_JSON,
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
                    .map(TrajectoryRow::step).toList();
            if (!childRows.isEmpty()) {
                childTrajectories.add(new AiChatTrajectoryData.ChildTrajectory(
                        child.guid(), child.conversationKind(), child.agentId(),
                        child.parentRequestId(), childRows));
            }
        }
        List<AiChatTrajectoryData.Step> rootRows = rows.stream()
                .filter(row -> internalConversationId.equals(row.conversationId()))
                .map(TrajectoryRow::step).toList();
        return new AiChatTrajectoryData(conversationId, rows.size(), false,
                rootRows, List.copyOf(childTrajectories));
    }

    private ChatHistoryMessage historyMessage(Record record) {
        String kind = record.get(AI_CHAT_STEP.MESSAGE_KIND);
        String reasoning = record.get(AI_CHAT_STEP.REASONING_CONTENT);
        String content = record.get(AI_CHAT_STEP.MESSAGE);
        Map<String, Object> storedExtra = serializer.deserializeMap(record.get(AI_CHAT_STEP.EXTRA_JSON));
        Map<String, Object> extra = storedExtra != null ? storedExtra : Map.of();
        boolean workerOwned = "SUBAGENT".equals(string(extra, "conversation_kind"))
                || "PARALLEL".equals(string(extra, "conversation_kind"));
        String role = switch (kind) {
            case "assistant", "user", "error", "progress", "tool_call", "guide" -> kind;
            case "workflow_result" -> "assistant";
            case "change_approval_batch_requested", "change_approval_decision" -> "guide";
            case "tool_call_update" -> "tool_call";
            case "agent_lifecycle" -> "agent_event";
            case "provider_error", "provider_retry" -> workerOwned ? "provider_event" : "debug";
            default -> "debug";
        };
        if ("debug".equals(role) && StringUtils.hasText(reasoning)) content = "Reasoning: " + reasoning;
        String requestId = record.get(AI_CHAT_STEP.REQUEST_ID);
        String toolCallId = string(extra, "tool_call_id");
        String toolName = string(extra, "tool_name");
        String toolStatus = string(extra, "tool_status");
        Long toolCallSequence = longValue(extra.get("tool_call_sequence"));
        Map<String, Object> metadata = new LinkedHashMap<>(extra);
        putIfPresent(metadata, "toolName", toolName);
        putIfPresent(metadata, "toolCallSeq", toolCallSequence);
        String subtype = switch (kind) {
            case "change_approval_batch_requested", "change_approval_decision" -> kind;
            case "agent_lifecycle" -> string(extra, "lifecycle_subtype");
            case "workflow_result" -> kind;
            case "provider_error", "provider_retry" -> kind;
            default -> toolStatus;
        };
        return new ChatHistoryMessage(record.get(AI_CHAT_STEP.STEP_SEQUENCE).intValue(), role,
                Objects.requireNonNullElse(content, ""), requestId, requestId,
                toolCallId != null ? requestId : null, toolCallId, toolCallSequence, subtype,
                AiChatTrajectoryStep.normalizeVisibility(record.get(AI_CHAT_STEP.VISIBILITY)),
                metadata);
    }

    private ChatHistoryMessage withIndex(ChatHistoryMessage message, int index) {
        return new ChatHistoryMessage(index, message.role(), message.content(), message.requestId(),
                message.turnId(), message.groupId(), message.toolCallId(), message.toolCallSequence(),
                message.subtype(), message.visibility(), message.metadata());
    }

    static List<ChatHistoryMessage> coalesceFinalWorkflowResult(List<ChatHistoryMessage> messages) {
        List<ChatHistoryMessage> projected = new ArrayList<>(messages.size());
        Set<String> finalAnswerRequests = new HashSet<>();
        Set<String> retainedWorkflowResultRequests = new HashSet<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatHistoryMessage message = messages.get(index);
            if ("assistant".equals(message.role())
                    && !"workflow_result".equals(message.subtype())) {
                finalAnswerRequests.add(message.requestId());
            }
            if ("workflow_result".equals(message.subtype())
                    && (finalAnswerRequests.contains(message.requestId())
                    || !retainedWorkflowResultRequests.add(message.requestId()))) continue;
            projected.add(message);
        }
        Collections.reverse(projected);
        return List.copyOf(projected);
    }

    private TrajectoryRow trajectoryRow(Record record) {
        LocalDateTime createdAt = record.get(AI_CHAT_STEP.CREATED_AT);
        String messageKind = record.get(AI_CHAT_STEP.MESSAGE_KIND);
        String modelName = record.get(AI_CHAT_STEP.MODEL_NAME);
        String reasoningEffort = record.get(AI_CHAT_STEP.REASONING_EFFORT);
        validateStoredSettingsChange(messageKind, modelName, reasoningEffort);
        return new TrajectoryRow(new AiChatConversationId(
                record.get(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID).toBigInteger()),
                record.get(AI_CHAT_STEP.STEP_SEQUENCE), record.get(AI_CHAT_STEP.REQUEST_ID),
                AiChatTrajectoryStep.normalizeSource(record.get(AI_CHAT_STEP.SOURCE)), messageKind,
                AiChatTrajectoryStep.normalizeVisibility(record.get(AI_CHAT_STEP.VISIBILITY)),
                record.get(AI_CHAT_STEP.MESSAGE), record.get(AI_CHAT_STEP.REASONING_CONTENT),
                modelName, reasoningEffort,
                serializer.deserializeListOfMaps(record.get(AI_CHAT_STEP.TOOL_CALLS_JSON)),
                serializer.deserializeMap(record.get(AI_CHAT_STEP.OBSERVATION_JSON)),
                serializer.deserializeMap(record.get(AI_CHAT_STEP.METRICS_JSON)),
                serializer.deserializeMap(record.get(AI_CHAT_STEP.EXTRA_JSON)),
                record.get(AI_CHAT_STEP.LLM_CALL_COUNT),
                byteBoolean(record.get(AI_CHAT_STEP.IS_COPIED_CONTEXT)),
                createdAt != null ? instant(createdAt) : Instant.EPOCH);
    }

    static void validateStoredSettingsChange(String messageKind, String modelName,
                                             String reasoningEffort) {
        if ("settings_change".equals(messageKind)
                && (modelName == null || reasoningEffort == null)) {
            throw new IllegalStateException(
                    "Stored AI chat settings_change step is incomplete: model_name, "
                            + "reasoning_effort are required and have no safe defaults.");
        }
    }

    private String latestPermissionMode(AiChatConversationId internalConversationId) {
        return dslContext.select(AI_CHAT_STEP.EXTRA_JSON)
                .from(AI_CHAT_STEP)
                .where(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID
                        .eq(access.valueOf(internalConversationId))
                        .and(AI_CHAT_STEP.MESSAGE_KIND.eq("user")))
                .orderBy(AI_CHAT_STEP.STEP_SEQUENCE.desc())
                .limit(1)
                .fetchOptional(record -> string(
                        serializer.deserializeMapOrEmpty(record.get(AI_CHAT_STEP.EXTRA_JSON)),
                        "permission_mode"))
                .filter(mode -> "auto".equals(mode) || "full_access".equals(mode))
                .orElse("ask");
    }

    private Instant instant(LocalDateTime value) {
        return value != null ? value.atZone(ZoneId.systemDefault()).toInstant() : null;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
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

    private Boolean byteBoolean(Byte value) {
        return value != null ? value != 0 : null;
    }

    private record Header(String title, Instant updatedAt) { }

    private record ChildTrajectoryHeader(AiChatConversationId internalId, String guid,
                                         String conversationKind,
                                         String agentId, String parentRequestId) { }

    private record TrajectoryRow(AiChatConversationId conversationId, long sequence,
                                 String requestId, String source, String messageKind,
                                 String visibility, String message, String reasoningContent,
                                 String modelName, String reasoningEffort,
                                 List<Map<String, Object>> toolCalls,
                                 Map<String, Object> observation, Map<String, Object> metrics,
                                 Map<String, Object> extra, Integer llmCallCount,
                                 Boolean isCopiedContext, Instant createdAt) {
        private AiChatTrajectoryData.Step step() {
            return new AiChatTrajectoryData.Step(sequence, requestId, source, messageKind,
                    visibility, message, reasoningContent, modelName, reasoningEffort,
                    toolCalls, observation, metrics, extra,
                    llmCallCount, isCopiedContext, createdAt);
        }
    }
}

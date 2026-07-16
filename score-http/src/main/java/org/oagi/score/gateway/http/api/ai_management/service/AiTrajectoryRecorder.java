package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.repository.ScoreChatMemoryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Records one ATIF-compatible step per model inference and correlates tool observations. */
public final class AiTrajectoryRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTrajectoryRecorder.class);
    public static final String PHASE_CONTEXT_KEY = "score.ai.trajectory.phase";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_AUDIT_TEXT_CHARS = 32_768;
    private static final String SAFE_TOOL_FAILURE_MESSAGE =
            "Tool execution failed. Details were recorded in the server log.";

    private final ScoreChatMemoryRepository repository;
    private final ObjectMapper objectMapper;
    private final ScoreUser requester;
    private final String conversationId;
    private final String requestId;
    private final String modelName;
    private final String reasoningEffort;
    private final String runtime;
    private final Map<String, Object> runtimeOptions;
    private final Consumer<AiExecutionEvent> events;
    private final AiContextBudgetService.Budget contextBudget;
    private final AtomicLong estimatedInputFloor;
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicLong toolSequence = new AtomicLong();
    private final Map<String, ConcurrentLinkedQueue<PendingTool>> pendingTools = new ConcurrentHashMap<>();
    private final Set<String> completedToolCallIds = ConcurrentHashMap.newKeySet();

    public AiTrajectoryRecorder(ScoreChatMemoryRepository repository, ObjectMapper objectMapper,
                                ScoreUser requester, String conversationId, String requestId,
                                Consumer<AiExecutionEvent> events) {
        this(repository, objectMapper, requester, conversationId, requestId,
                null, null, null, Map.of(), events, null, 0L);
    }

    public AiTrajectoryRecorder(ScoreChatMemoryRepository repository, ObjectMapper objectMapper,
                                ScoreUser requester, String conversationId, String requestId,
                                String modelName, String reasoningEffort, String runtime,
                                Map<String, Object> runtimeOptions,
                                Consumer<AiExecutionEvent> events) {
        this(repository, objectMapper, requester, conversationId, requestId, modelName,
                reasoningEffort, runtime, runtimeOptions, events, null, 0L);
    }

    public AiTrajectoryRecorder(ScoreChatMemoryRepository repository, ObjectMapper objectMapper,
                                ScoreUser requester, String conversationId, String requestId,
                                String modelName, String reasoningEffort, String runtime,
                                Map<String, Object> runtimeOptions,
                                Consumer<AiExecutionEvent> events,
                                AiContextBudgetService.Budget contextBudget,
                                long estimatedInputFloor) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requester = requester;
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.modelName = modelName;
        this.reasoningEffort = reasoningEffort;
        this.runtime = runtime;
        this.runtimeOptions = runtimeOptions != null ? Map.copyOf(runtimeOptions) : Map.of();
        this.events = events != null ? events : ignored -> {};
        this.contextBudget = contextBudget;
        this.estimatedInputFloor = new AtomicLong(Math.max(0L, estimatedInputFloor));
    }

    public void progress(String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        repository.append(requester, conversationId, new AiChatTrajectoryStep(
                requestId, "system", "progress", "debug", content, null, null,
                null, null, null, Map.of("event_sequence", eventSequence.incrementAndGet()),
                0, null, Instant.now()));
        events.accept(AiExecutionEvent.progress(content));
    }

    /** Emits streamed visible content without persisting partial duplicates. */
    public void contentDelta(String content) {
        if (content != null && !content.isEmpty()) {
            events.accept(AiExecutionEvent.contentDelta(content));
        }
    }

    public void mutationConfirmationRequired(AiMutationConfirmationNotice notice) {
        if (notice == null) {
            return;
        }
        events.accept(AiExecutionEvent.detail("mutation_confirmation_required",
                "A data-changing action requires explicit approval.", Map.of(
                        "confirmationRequestId", notice.confirmationRequestId(),
                        "status", notice.status(),
                        "expiresAt", notice.expiresAt().toString(),
                        "toolName", notice.toolName(),
                        "argumentsSummary", notice.argumentsSummary())));
    }

    public void elicitationRequired(AiElicitationService.Notice notice) {
        if (notice == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("elicitationId", notice.elicitationId());
        metadata.put("expiresAt", notice.expiresAt().toString());
        metadata.put("mode", "form");
        metadata.put("message", notice.message());
        metadata.put("requestedSchema", notice.requestedSchema());
        events.accept(AiExecutionEvent.detail("elicitation_required",
                "The assistant needs your input before it can continue.", metadata));
    }

    public void recordModelResponse(ChatResponse response, String phase) {
        if (response == null || response.getResults().isEmpty()) {
            return;
        }
        String normalizedPhase = StringUtils.hasText(phase) ? phase : "model";
        boolean reasoningPresent = StringUtils.hasText(reasoning(response.getResults()));
        String message = visibleMessage(response.getResults());
        List<Map<String, Object>> toolCalls = toolCalls(response.getResults());
        List<Map<String, Object>> auditedToolCalls = auditToolCalls(toolCalls);
        MetricsSnapshot metricsSnapshot = metrics(response);
        Map<String, Object> metrics = metricsSnapshot != null ? metricsSnapshot.metrics() : null;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("phase", normalizedPhase);
        if (StringUtils.hasText(response.getMetadata().getId())) {
            extra.put("provider_response_id", response.getMetadata().getId());
        }
        if (StringUtils.hasText(response.getMetadata().getModel())) {
            extra.put("provider_model", response.getMetadata().getModel());
        }
        if (reasoningPresent) {
            extra.put("reasoning_present", true);
        }

        AiChatStoredStep stored = repository.append(requester, conversationId,
                new AiChatTrajectoryStep(requestId, "agent", "model_call", "debug",
                        Objects.requireNonNullElse(message, ""), null,
                        StringUtils.hasText(modelName) ? modelName : response.getMetadata().getModel(),
                        reasoningEffort, runtime, runtimeOptions,
                        auditedToolCalls, null, metrics, extra, 1, null, Instant.now()));

        ObservationAccumulator observations = new ObservationAccumulator(stored.id(), toolCalls);
        for (Map<String, Object> call : toolCalls) {
            String name = Objects.toString(call.get("function_name"), "tool");
            String callId = Objects.toString(call.get("tool_call_id"), UUID.randomUUID().toString());
            pendingTools.computeIfAbsent(name, ignored -> new ConcurrentLinkedQueue<>())
                    .add(new PendingTool(callId, name, call.get("arguments"), observations,
                            toolSequence.getAndIncrement()));
        }
        if (metricsSnapshot != null && contextBudget != null) {
            emitContextUsage(contextBudget.usage(metricsSnapshot.contextInputTokens(),
                    metricsSnapshot.estimated(), metricsSnapshot.estimated() ? "estimate_floor" : "provider"));
        }
    }

    public void recordToolResponses(List<Message> messages) {
        if (messages == null) {
            return;
        }
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                if (response.id().startsWith("approved-")) {
                    // The exact approved invocation was already recorded by RecordingToolCallback.
                    // This synthetic response only restores model context after the approval turn.
                    continue;
                }
                if (completedToolCallIds.contains(response.id())) {
                    continue;
                }
                PendingTool pending = pendingTools.computeIfAbsent(response.name(), ignored -> new ConcurrentLinkedQueue<>())
                        .poll();
                if (pending == null) {
                    pending = new PendingTool(response.id(), response.name(), Map.of(),
                            new ObservationAccumulator(0L, List.of()), toolSequence.getAndIncrement());
                }
                toolStarted(pending);
                toolCompleted(pending, response.responseData(), null, Duration.ZERO);
            }
        }
    }

    public ToolCallbackProvider recordingTools(ToolCallbackProvider delegate) {
        return recordingTools(delegate, Long.MAX_VALUE);
    }

    public ToolCallbackProvider recordingTools(ToolCallbackProvider delegate, long toolOutputTokenLimit) {
        ToolCallback[] callbacks = delegate != null ? delegate.getToolCallbacks() : new ToolCallback[0];
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int index = 0; index < callbacks.length; index++) {
            wrapped[index] = new RecordingToolCallback(callbacks[index], toolOutputTokenLimit);
        }
        return () -> wrapped;
    }

    public String limitToolOutput(String output, long toolOutputTokenLimit) {
        return limitToolOutput(output, toolOutputTokenLimit, "approved_tool");
    }

    public String limitToolOutput(String output, long toolOutputTokenLimit, String toolName) {
        BoundedToolOutput bounded = reserveToolOutput(output,
                toolOutputTokenLimit > 0 ? toolOutputTokenLimit : Long.MAX_VALUE);
        emitToolOutputTruncated(bounded, toolOutputTokenLimit, toolName);
        emitToolOutputUsage(bounded);
        return bounded.value();
    }

    public void contextCompacted(String reason, long beforeTokens, AiContextUsageInfo usage,
                                 boolean automatic) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", StringUtils.hasText(reason) ? reason : "threshold");
        metadata.put("automatic", automatic);
        metadata.put("beforeInputTokens", Math.max(0L, beforeTokens));
        if (usage != null) metadata.put("contextUsage", usage);
        events.accept(AiExecutionEvent.detail("context_compacted",
                automatic ? "Conversation context was compacted automatically."
                        : "Conversation context was compacted.", metadata));
    }

    public void contextUsage(AiContextUsageInfo usage) {
        if (usage != null) emitContextUsage(usage);
    }

    public void resetEstimatedInputFloor(long inputTokens) {
        estimatedInputFloor.set(Math.max(0L, inputTokens));
    }

    private String reasoning(List<Generation> generations) {
        return generations.stream()
                .map(Generation::getOutput)
                .filter(this::isReasoning)
                .map(AssistantMessage::getText)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse(null);
    }

    private String visibleMessage(List<Generation> generations) {
        return generations.stream()
                .map(Generation::getOutput)
                .filter(output -> !isReasoning(output))
                .map(AssistantMessage::getText)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + right)
                .orElse("");
    }

    private boolean isReasoning(AssistantMessage output) {
        return output.getMetadata().containsKey("signature")
                || output.getMetadata().containsKey("data")
                || Boolean.TRUE.equals(output.getMetadata().get("thinking"));
    }

    private List<Map<String, Object>> toolCalls(List<Generation> generations) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (Generation generation : generations) {
            for (AssistantMessage.ToolCall call : generation.getOutput().getToolCalls()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tool_call_id", StringUtils.hasText(call.id()) ? call.id() : UUID.randomUUID().toString());
                item.put("function_name", call.name());
                item.put("arguments", arguments(call.arguments()));
                calls.add(item);
            }
        }
        return calls;
    }

    private List<Map<String, Object>> auditToolCalls(List<Map<String, Object>> toolCalls) {
        return toolCalls.stream().map(call -> {
            Map<String, Object> audited = new LinkedHashMap<>(call);
            audited.put("arguments", boundedRedactedValue(call.get("arguments")));
            return Map.copyOf(audited);
        }).toList();
    }

    private Map<String, Object> arguments(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            return Map.of("raw", json);
        }
    }

    private MetricsSnapshot metrics(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        long promptTokens = Objects.requireNonNullElse(usage.getPromptTokens(), 0);
        promptTokens += Objects.requireNonNullElse(usage.getCacheReadInputTokens(), 0L);
        promptTokens += Objects.requireNonNullElse(usage.getCacheWriteInputTokens(), 0L);
        metrics.put("prompt_tokens", promptTokens);
        putIfPresent(metrics, "completion_tokens", usage.getCompletionTokens());
        putIfPresent(metrics, "cached_tokens", usage.getCacheReadInputTokens());
        if (usage.getCacheWriteInputTokens() != null) {
            metrics.put("extra", Map.of("cache_creation_input_tokens", usage.getCacheWriteInputTokens()));
        }
        long contextInputTokens = Math.max(promptTokens, estimatedInputFloor.get());
        boolean estimated = contextInputTokens > promptTokens;
        estimatedInputFloor.accumulateAndGet(contextInputTokens, Math::max);
        metrics.put("context_input_tokens", contextInputTokens);
        metrics.put("context_estimated", estimated);
        return new MetricsSnapshot(Map.copyOf(metrics), contextInputTokens, estimated);
    }

    private void emitContextUsage(AiContextUsageInfo usage) {
        events.accept(AiExecutionEvent.detail("context_usage", "Context usage updated.",
                Map.of("contextUsage", usage)));
    }

    private PendingTool pending(String toolName, String input) {
        ConcurrentLinkedQueue<PendingTool> queue = pendingTools.get(toolName);
        Object parsedArguments = arguments(input);
        PendingTool pending = null;
        if (queue != null) {
            pending = queue.stream()
                    .filter(candidate -> Objects.equals(candidate.arguments(), parsedArguments))
                    .findFirst()
                    .orElse(null);
            if (pending != null) {
                queue.remove(pending);
            } else {
                pending = queue.poll();
            }
        }
        if (pending != null) {
            return pending;
        }
        return new PendingTool(UUID.randomUUID().toString(), toolName, parsedArguments,
                new ObservationAccumulator(0L, List.of()), toolSequence.getAndIncrement());
    }

    private void toolStarted(PendingTool pending) {
        events.accept(AiExecutionEvent.tool("started", "Calling " + pending.name() + ".",
                pending.id(), pending.name(), pending.sequence()));
    }

    private void toolCompleted(PendingTool pending, String output, Throwable failure, Duration duration) {
        if (!completedToolCallIds.add(pending.id())) {
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_call_id", pending.id());
        result.put("content", auditText(output));
        result.put("extra", Map.of(
                "duration_ms", duration.toMillis(),
                "success", failure == null));
        pending.observations().results().put(pending.id(), result);
        if (pending.observations().stepId() > 0) {
            repository.updateObservation(requester, conversationId, pending.observations().stepId(),
                    Map.of("results", pending.observations().orderedResults()));
        }

        String status = failure == null ? "completed" : "failed";
        String detail = toolDetail(pending, output, failure);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tool_call_id", pending.id());
        extra.put("tool_name", pending.name());
        extra.put("tool_status", status);
        extra.put("tool_call_sequence", pending.sequence());
        extra.put("arguments", boundedRedactedValue(pending.arguments()));
        extra.put("duration_ms", duration.toMillis());
        extra.put("success", failure == null);
        repository.append(requester, conversationId, new AiChatTrajectoryStep(
                requestId, "agent", "tool_call", "debug", detail, null, modelName,
                reasoningEffort, runtime, runtimeOptions,
                null, null, null, extra, 0, null, Instant.now()));
        events.accept(AiExecutionEvent.tool(status,
                failure == null ? pending.name() + " completed." : pending.name() + " failed.",
                pending.id(), pending.name(), pending.sequence(), Map.of("toolDetail", detail)));
    }

    private String toolDetail(PendingTool pending, String output, Throwable failure) {
        StringBuilder detail = new StringBuilder(pending.name())
                .append("\nArguments: ").append(toJson(boundedRedactedValue(pending.arguments())));
        if (failure == null) {
            detail.append("\nResult: ").append(auditText(output));
        } else {
            detail.append("\nError: ").append(SAFE_TOOL_FAILURE_MESSAGE);
        }
        return detail.toString();
    }

    private String auditText(String value) {
        String source = Objects.requireNonNullElse(value, "");
        int omitted = Math.max(0, source.length() - MAX_AUDIT_TEXT_CHARS);
        String candidate = omitted > 0 ? source.substring(0, MAX_AUDIT_TEXT_CHARS) : source;
        String sanitized = sanitizeText(candidate);
        try {
            Object parsed = objectMapper.readValue(candidate, Object.class);
            if (parsed instanceof Map<?, ?> || parsed instanceof List<?>) {
                sanitized = objectMapper.writeValueAsString(redact(parsed));
            }
        } catch (JsonProcessingException ignored) {
            // Non-JSON tool output is redacted with the conservative text pattern.
        }
        if (sanitized.length() > MAX_AUDIT_TEXT_CHARS) {
            omitted += sanitized.length() - MAX_AUDIT_TEXT_CHARS;
            sanitized = sanitized.substring(0, MAX_AUDIT_TEXT_CHARS);
        }
        return omitted > 0 ? sanitized + "\n[TRUNCATED " + omitted + " CHARACTERS]" : sanitized;
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = Objects.toString(key);
                result.put(name, AiSensitiveDataRedactor.isSensitiveKey(name)
                        ? "[REDACTED]" : redact(item));
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redact).toList();
        }
        return value instanceof String text ? auditText(text) : value;
    }

    private Object boundedRedactedValue(Object value) {
        Object redacted = redact(value);
        String serialized = toJson(redacted);
        if (serialized.length() <= MAX_AUDIT_TEXT_CHARS) {
            return redacted;
        }
        return Map.of("truncated", true, "summary", boundedText(serialized));
    }

    private String sanitizeText(String value) {
        return AiSensitiveDataRedactor.redactText(value);
    }

    private String boundedText(String value) {
        if (value.length() <= MAX_AUDIT_TEXT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_AUDIT_TEXT_CHARS)
                + "\n[TRUNCATED " + (value.length() - MAX_AUDIT_TEXT_CHARS) + " CHARACTERS]";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return Objects.toString(value);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private final class RecordingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final long toolOutputTokenLimit;

        private RecordingToolCallback(ToolCallback delegate, long toolOutputTokenLimit) {
            this.delegate = delegate;
            this.toolOutputTokenLimit = toolOutputTokenLimit > 0 ? toolOutputTokenLimit : Long.MAX_VALUE;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return call(input, new ToolContext(Map.of()));
        }

        @Override
        public String call(String input, ToolContext context) {
            PendingTool pending = pending(getToolDefinition().name(), input);
            toolStarted(pending);
            Instant started = Instant.now();
            try {
                String output = delegate.call(input, context);
                toolCompleted(pending, output, null, Duration.between(started, Instant.now()));
                BoundedToolOutput bounded = reserveToolOutput(output, toolOutputTokenLimit);
                emitToolOutputTruncated(bounded, toolOutputTokenLimit, pending.name());
                emitToolOutputUsage(bounded);
                return bounded.value();
            } catch (RuntimeException exception) {
                LOGGER.warn("AI tool {} failed for request {}", pending.name(), requestId, exception);
                toolCompleted(pending, null, exception, Duration.between(started, Instant.now()));
                throw exception;
            }
        }
    }

    private BoundedToolOutput boundedToolOutput(String output, long tokenLimit) {
        String source = Objects.requireNonNullElse(output, "");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        long byteLimit = tokenLimit == Long.MAX_VALUE || tokenLimit > Integer.MAX_VALUE / 3L
                ? Integer.MAX_VALUE : Math.max(0L, tokenLimit) * 3L;
        if (bytes.length <= byteLimit) {
            return new BoundedToolOutput(source, false, bytes.length, bytes.length);
        }
        int maximumBytes = (int) byteLimit;
        String suffix = "\n[TOOL OUTPUT TRUNCATED: rerun the tool with narrower filters or pagination.]";
        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        if (maximumBytes <= suffixBytes) {
            String marker = suffix.substring(0, Math.min(maximumBytes, suffix.length()));
            return new BoundedToolOutput(marker, true, bytes.length,
                    marker.getBytes(StandardCharsets.UTF_8).length);
        }
        int prefixBudget = Math.max(0, maximumBytes - suffixBytes);
        int chars = 0;
        int usedBytes = 0;
        while (chars < source.length()) {
            int codePoint = source.codePointAt(chars);
            int width = Character.charCount(codePoint);
            int encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + encoded > prefixBudget) break;
            chars += width;
            usedBytes += encoded;
        }
        String bounded = source.substring(0, chars) + suffix;
        int returnedBytes = bounded.getBytes(StandardCharsets.UTF_8).length;
        return new BoundedToolOutput(bounded, true, bytes.length, returnedBytes);
    }

    private synchronized BoundedToolOutput reserveToolOutput(String output, long configuredLimit) {
        long effectiveLimit = configuredLimit;
        if (contextBudget != null) {
            long remaining = Math.max(0L, contextBudget.safeInputLimit() - estimatedInputFloor.get());
            effectiveLimit = Math.min(effectiveLimit, remaining);
        }
        BoundedToolOutput bounded = boundedToolOutput(output, effectiveLimit);
        growEstimatedInputFloor(bounded.returnedBytes());
        return bounded;
    }

    private void emitToolOutputTruncated(BoundedToolOutput bounded, long configuredLimit,
                                         String toolName) {
        if (!bounded.truncated()) return;
        String safeToolName = StringUtils.hasText(toolName) ? toolName : "tool";
        events.accept(AiExecutionEvent.detail("tool_output_truncated",
                safeToolName + " returned more data than the active context budget allows.", Map.of(
                        "toolName", safeToolName,
                        "originalUtf8Bytes", bounded.originalBytes(),
                        "returnedUtf8Bytes", bounded.returnedBytes(),
                        "toolOutputTokenLimit", configuredLimit)));
    }

    private void emitToolOutputUsage(BoundedToolOutput bounded) {
        if (contextBudget == null || bounded.returnedBytes() <= 0) return;
        emitContextUsage(contextBudget.usage(estimatedInputFloor.get(), true,
                "tool_output_estimate"));
    }

    private void growEstimatedInputFloor(int utf8Bytes) {
        long additional = utf8Bytes <= 0 ? 0L : (utf8Bytes + 2L) / 3L;
        estimatedInputFloor.updateAndGet(current -> current > Long.MAX_VALUE - additional
                ? Long.MAX_VALUE : current + additional);
    }

    private record PendingTool(String id, String name, Object arguments,
                               ObservationAccumulator observations, long sequence) {}

    private record MetricsSnapshot(Map<String, Object> metrics, long contextInputTokens,
                                   boolean estimated) {}

    private record BoundedToolOutput(String value, boolean truncated, int originalBytes,
                                     int returnedBytes) {}

    private record ObservationAccumulator(long stepId, List<Map<String, Object>> toolCalls,
                                           Map<String, Map<String, Object>> results) {

        private ObservationAccumulator(long stepId, List<Map<String, Object>> toolCalls) {
            this(stepId, List.copyOf(toolCalls), new ConcurrentHashMap<>());
        }

        private List<Map<String, Object>> orderedResults() {
            List<Map<String, Object>> ordered = new ArrayList<>();
            for (Map<String, Object> call : toolCalls) {
                Map<String, Object> result = results.get(Objects.toString(call.get("tool_call_id"), ""));
                if (result != null) {
                    ordered.add(result);
                }
            }
            if (ordered.isEmpty()) {
                ordered.addAll(results.values());
            }
            return ordered;
        }
    }
}

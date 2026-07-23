package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AiSensitiveDataRedactor;
import org.oagi.score.gateway.http.api.ai_management.model.AiBoundedToolOutput;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiMetricsSnapshot;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiObservationAccumulator;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingMutationApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingTool;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.tool.AiMutationToolGuard;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolFailureMessage;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolRetryMessage;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolRetryTracker;
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

/**
 * Records ATIF-compatible execution history and publishes content-free lifecycle facts
 * through {@link ExecutionObserver} for independent consumers such as OpenTelemetry.
 */
public final class AiTrajectoryRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTrajectoryRecorder.class);
    public static final String PHASE_CONTEXT_KEY = "score.ai.trajectory.phase";
    /** Stable trajectory wire value recognized by external verifiers. */
    public static final String FANOUT_USAGE_STEP_KIND = "fanout_usage";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_AUDIT_TEXT_CHARS = 32_768;

    private final AiChatConversationRepository repository;
    private final ObjectMapper objectMapper;
    private final ScoreUser requester;
    private final String conversationId;
    private final String requestId;
    private final String modelName;
    private final String reasoningEffort;
    private final Consumer<AiExecutionEvent> realtimeEvents;
    private final AiContextBudget contextBudget;
    private final ExecutionScope executionScope;
    private final ExecutionObserver observer;
    private final ExecutionObservationContext observationContext;
    private volatile PromptTokenAccounting promptTokenAccounting;
    private final AtomicLong estimatedInputFloor;
    private final AtomicLong eventSequence;
    private final AtomicLong toolSequence;
    private final Map<String, Object> traceContext;
    private final boolean subagentScope;
    private final AiChatConversationKind conversationKind;
    private volatile String modelProvider = "unknown";
    private volatile String requestModelName;
    private final AtomicLong ownPromptTokens = new AtomicLong();
    private final AtomicLong ownCompletionTokens = new AtomicLong();
    private final AtomicLong ownModelCalls = new AtomicLong();
    private final Map<String, ConcurrentLinkedQueue<AiPendingTool>> pendingTools = new ConcurrentHashMap<>();
    private final Set<String> completedToolCallIds = ConcurrentHashMap.newKeySet();
    private final Set<String> narratedToolCallIds = ConcurrentHashMap.newKeySet();
    private final AiToolRetryTracker toolRetryTracker = new AiToolRetryTracker();
    private final AtomicLong completedToolCalls = new AtomicLong();
    private final AtomicLong completedDomainToolCalls = new AtomicLong();
    private final AtomicLong successfulDomainToolCalls = new AtomicLong();
    private final AtomicLong requestExecutedDomainToolCalls;
    private final Set<String> requestPendingApprovalIds;
    private final Set<String> mcpToolNames = ConcurrentHashMap.newKeySet();
    private volatile String mcpServerName = "unknown";
    private volatile String mcpProtocolVersion;
    private volatile String mcpServerAddress;
    private volatile long mcpServerPort = -1;
    private volatile String mcpNetworkProtocolName;
    private volatile String mcpNetworkTransport;
    private final AtomicLong executedMutationToolCalls = new AtomicLong();
    private volatile Set<String> readOnlyToolNames = Set.of();
    private volatile boolean sealed;
    private volatile String lastGuideContent;
    private volatile AiToolRetryMessage.Language retryMessageLanguage =
            AiToolRetryMessage.Language.ENGLISH;

    public AiTrajectoryRecorder(AiChatConversationRepository repository, ObjectMapper objectMapper,
                                ScoreUser requester, String conversationId, String requestId,
                                Consumer<AiExecutionEvent> events) {
        this(repository, objectMapper, requester, conversationId, requestId,
                null, null, events, null, 0L);
    }

    public AiTrajectoryRecorder(AiChatConversationRepository repository, ObjectMapper objectMapper,
                                ScoreUser requester, String conversationId, String requestId,
                                String modelName, String reasoningEffort,
                                Consumer<AiExecutionEvent> events) {
        this(repository, objectMapper, requester, conversationId, requestId, modelName,
                reasoningEffort, events, null, 0L);
    }

    public AiTrajectoryRecorder(AiChatConversationRepository repository, ObjectMapper objectMapper,
                                ScoreUser requester, String conversationId, String requestId,
                                String modelName, String reasoningEffort,
                                Consumer<AiExecutionEvent> events,
                                AiContextBudget contextBudget,
                                long estimatedInputFloor) {
        this(repository, objectMapper, requester, conversationId, requestId, modelName,
                reasoningEffort, events, contextBudget, estimatedInputFloor, Map.of());
    }

    public AiTrajectoryRecorder(AiChatConversationRepository repository, ObjectMapper objectMapper,
                                ScoreUser requester, String conversationId, String requestId,
                                String modelName, String reasoningEffort,
                                Consumer<AiExecutionEvent> events,
                                AiContextBudget contextBudget,
                                long estimatedInputFloor,
                                Map<String, Object> traceContext) {
        this(repository, objectMapper, requester, conversationId, requestId, modelName,
                reasoningEffort, events, contextBudget, estimatedInputFloor, traceContext,
                null, ExecutionObserver.noop(), ExecutionObservationContext.noop());
    }

    public AiTrajectoryRecorder(AiChatConversationRepository repository, ObjectMapper objectMapper,
                                ScoreUser requester, String conversationId, String requestId,
                                String modelName, String reasoningEffort,
                                Consumer<AiExecutionEvent> events,
                                AiContextBudget contextBudget,
                                long estimatedInputFloor,
                                Map<String, Object> traceContext,
                                ExecutionScope executionScope,
                                ExecutionObserver observer,
                                ExecutionObservationContext observationContext) {
        this(repository, objectMapper, requester, conversationId, requestId, modelName,
                reasoningEffort, events, contextBudget,
                estimatedInputFloor, PromptTokenAccounting.UNKNOWN,
                new AtomicLong(), new AtomicLong(),
                new AtomicLong(), ConcurrentHashMap.newKeySet(), traceContext, executionScope,
                observer, observationContext,
                false, AiChatConversationKind.ROOT);
    }

    private AiTrajectoryRecorder(AiChatConversationRepository repository, ObjectMapper objectMapper,
                                 ScoreUser requester, String conversationId, String requestId,
                                 String modelName, String reasoningEffort,
                                 Consumer<AiExecutionEvent> events,
                                 AiContextBudget contextBudget,
                                 long estimatedInputFloor,
                                 PromptTokenAccounting promptTokenAccounting,
                                 AtomicLong eventSequence, AtomicLong toolSequence,
                                 AtomicLong requestExecutedDomainToolCalls,
                                 Set<String> requestPendingApprovalIds,
                                 Map<String, Object> traceContext,
                                 ExecutionScope executionScope,
                                 ExecutionObserver observer,
                                 ExecutionObservationContext observationContext,
                                 boolean subagentScope,
                                 AiChatConversationKind conversationKind) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requester = requester;
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.modelName = modelName;
        this.requestModelName = modelName;
        this.reasoningEffort = reasoningEffort;
        this.realtimeEvents = events != null ? events : ignored -> {};
        this.contextBudget = contextBudget;
        this.executionScope = executionScope;
        this.observer = observer != null ? observer : ExecutionObserver.noop();
        this.observationContext = observationContext != null
                ? observationContext : ExecutionObservationContext.noop();
        this.promptTokenAccounting = promptTokenAccounting;
        this.estimatedInputFloor = new AtomicLong(Math.max(0L, estimatedInputFloor));
        this.eventSequence = eventSequence;
        this.toolSequence = toolSequence;
        this.requestExecutedDomainToolCalls = requestExecutedDomainToolCalls;
        this.requestPendingApprovalIds = requestPendingApprovalIds;
        this.traceContext = traceContext != null ? Map.copyOf(traceContext) : Map.of();
        this.subagentScope = subagentScope;
        this.conversationKind = conversationKind;
    }

    /**
     * Creates an isolated tool-correlation recorder with shared, globally ordered event
     * sequences. Children act as fan-out containers: their model-call metrics are marked
     * subagent-scoped so they never drive the conversation's context floor, and the parent
     * aggregates their usage through {@link #recordFanOutUsage(String, List)} on completion.
     */
    public AiTrajectoryRecorder fork(Map<String, Object> namespace) {
        Map<String, Object> childContext = traceMetadata(namespace);
        AiTrajectoryRecorder child = new AiTrajectoryRecorder(
                repository, objectMapper, requester, conversationId, requestId,
                modelName, reasoningEffort, realtimeEvents, contextBudget,
                estimatedInputFloor.get(), promptTokenAccounting, eventSequence, toolSequence,
                requestExecutedDomainToolCalls, requestPendingApprovalIds, childContext,
                executionScope, observer, observationContext, true, conversationKind);
        child.retryMessageLanguage = retryMessageLanguage;
        return child;
    }

    /** Creates a durable SUBAGENT conversation for a delegated agent. */
    public AiTrajectoryRecorder forkSubagent(String agentId, String assignment,
                                              Map<String, Object> namespace) {
        return forkChild(AiChatConversationKind.SUBAGENT, agentId, assignment, namespace);
    }

    /** Creates a durable PARALLEL conversation for one parallel workflow task. */
    public AiTrajectoryRecorder forkParallelExecution(String workerId, String assignment,
                                                       Map<String, Object> namespace) {
        return forkChild(AiChatConversationKind.PARALLEL, workerId, assignment, namespace);
    }

    private AiTrajectoryRecorder forkChild(AiChatConversationKind kind, String workerId,
                                           String assignment, Map<String, Object> namespace) {
        String childConversationId = repository.openChild(
                conversationId, requestId, kind, workerId, assignment);
        Map<String, Object> childNamespace = new LinkedHashMap<>(
                namespace != null ? namespace : Map.of());
        childNamespace.put("child_conversation_id", childConversationId);
        childNamespace.put("conversation_kind", kind.name());
        childNamespace.put("execution_kind",
                kind == AiChatConversationKind.PARALLEL ? "parallel" : "multi_agent");
        AiTrajectoryRecorder child = new AiTrajectoryRecorder(
                repository, objectMapper, requester, childConversationId, requestId,
                modelName, reasoningEffort, realtimeEvents, contextBudget,
                estimatedInputFloor.get(), promptTokenAccounting, eventSequence, toolSequence,
                requestExecutedDomainToolCalls, requestPendingApprovalIds,
                traceMetadata(childNamespace), executionScope, observer, observationContext,
                true, kind);
        child.retryMessageLanguage = retryMessageLanguage;
        repository.append(childConversationId, new AiChatTrajectoryStep(
                requestId, "system", "settings_change", "debug",
                "Child execution settings initialized.", null, modelName, reasoningEffort,
                null, null, null,
                child.traceMetadata(Map.of("agent_id", workerId)), 0, null, Instant.now()));
        repository.append(childConversationId, new AiChatTrajectoryStep(
                requestId, "user",
                kind == AiChatConversationKind.PARALLEL ? "parallel_assignment" : "assignment",
                "visible",
                Objects.requireNonNullElse(assignment, ""), null, modelName, reasoningEffort,
                null, null, null,
                child.traceMetadata(Map.of("copied_from_parent", true)), 0, true, Instant.now()));
        return child;
    }

    public String conversationId() {
        return conversationId;
    }

    public AiChatConversationKind conversationKind() {
        return conversationKind;
    }

    public String requestId() { return requestId; }
    public String modelName() { return modelName; }
    public String requestModelName() { return requestModelName; }
    public String modelProvider() { return modelProvider; }

    public Map<String, Object> observationContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("fanout_id", "node_id", "parent_node_id",
                "workflow", "workflow_iteration")) {
            if (traceContext.get(key) != null) context.put(key, traceContext.get(key));
        }
        return Map.copyOf(context);
    }

    public void mcpToolNames(java.util.Collection<String> toolNames) {
        mcpToolNames.clear();
        if (toolNames != null) {
            toolNames.stream().filter(StringUtils::hasText).map(String::strip)
                    .forEach(mcpToolNames::add);
        }
    }

    public void mcpServerName(String serverName) {
        this.mcpServerName = StringUtils.hasText(serverName) ? serverName.strip() : "unknown";
    }

    public void mcpTelemetry(String serverName, String protocolVersion, String serverAddress,
                             long serverPort, String networkProtocolName,
                             String networkTransport) {
        mcpServerName(serverName);
        this.mcpProtocolVersion = normalizedMetadata(protocolVersion);
        this.mcpServerAddress = normalizedMetadata(serverAddress);
        this.mcpServerPort = serverPort > 0 && serverPort <= 65_535 ? serverPort : -1;
        this.mcpNetworkProtocolName = normalizedMetadata(networkProtocolName);
        this.mcpNetworkTransport = normalizedMetadata(networkTransport);
    }

    private static String normalizedMetadata(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /** Selects the provider-specific mapping from Spring AI usage to ATIF prompt totals. */
    public void useModelProvider(String providerType) {
        this.modelProvider = StringUtils.hasText(providerType) ? providerType.strip() : "unknown";
        this.promptTokenAccounting = PromptTokenAccounting.fromProviderType(providerType);
    }

    /** Configures the exact model identifier sent to the provider for GenAI telemetry. */
    public void useModelProvider(String providerType, String requestModelName) {
        useModelProvider(providerType);
        this.requestModelName = StringUtils.hasText(requestModelName)
                ? requestModelName.strip() : modelName;
    }

    /** Snapshot of the model usage this recorder observed, keyed by its fan-out namespace. */
    public AiUsageSnapshot usageSnapshot() {
        return new AiUsageSnapshot(
                traceContext.get("node_id") != null ? traceContext.get("node_id").toString() : null,
                traceContext.get("agent_name") != null ? traceContext.get("agent_name").toString() : null,
                ownPromptTokens.get(), ownCompletionTokens.get(), ownModelCalls.get());
    }

    /**
     * Records one authoritative usage step for a settled fan-out, aggregated from the
     * container's child recorders. Unlike subagent-scoped model calls, this step's metrics
     * may drive the conversation's context floor: context_input_tokens reflects the root
     * conversation projection, not any transient synthesis prompt.
     */
    public synchronized void recordFanOutUsage(String fanoutId, List<AiUsageSnapshot> agents) {
        recordFanOutUsage(fanoutId, "multi_agent", agents);
    }

    public synchronized void recordFanOutUsage(String fanoutId, String executionKind,
                                                List<AiUsageSnapshot> agents) {
        if (sealed) {
            return;
        }
        List<AiUsageSnapshot> settled = agents != null
                ? agents.stream().filter(Objects::nonNull).toList() : List.of();
        Map<String, Object> metrics = new LinkedHashMap<>();
        // Fan-out totals use dedicated keys: every child model call already persisted
        // its own prompt/completion metrics, so reusing the standard keys would
        // double-count fan-out tokens in exported trajectory totals.
        metrics.put("fanout_prompt_tokens", settled.stream().mapToLong(AiUsageSnapshot::promptTokens).sum());
        metrics.put("fanout_completion_tokens", settled.stream().mapToLong(AiUsageSnapshot::completionTokens).sum());
        metrics.put("context_input_tokens", estimatedInputFloor.get());
        metrics.put("context_estimated", true);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("fanout_id", Objects.requireNonNullElse(fanoutId, "unknown"));
        extra.put("execution_kind", Objects.requireNonNullElse(executionKind, "multi_agent"));
        extra.put("agents", settled.stream().map(agent -> {
            Map<String, Object> item = new LinkedHashMap<>();
            if (agent.nodeId() != null) {
                item.put("node_id", agent.nodeId());
            }
            if (agent.agentName() != null) {
                item.put("agent_name", agent.agentName());
            }
            item.put("prompt_tokens", agent.promptTokens());
            item.put("completion_tokens", agent.completionTokens());
            item.put("model_calls", agent.modelCalls());
            return Map.copyOf(item);
        }).toList());
        repository.append(conversationId, new AiChatTrajectoryStep(
                requestId, "system", FANOUT_USAGE_STEP_KIND, "debug",
                "parallel".equals(executionKind)
                        ? "Parallel workflow usage settled." : "Multi-agent fan-out usage settled.",
                null, modelName,
                reasoningEffort,
                null, null, Map.copyOf(metrics), traceMetadata(extra), 0, null, Instant.now()));
        if (contextBudget != null) {
            emitContextUsage(contextBudget.usage(estimatedInputFloor.get(), true, "fanout_settled"));
        }
    }

    /** Persists and emits one fan-out or specialist lifecycle transition. */
    public synchronized void lifecycle(String subtype, String content, Map<String, Object> metadata) {
        if (sealed) return;
        appendLifecycle(subtype, content, metadata);
    }

    /** Atomically records the terminal node transition and rejects every later provider callback. */
    public synchronized void terminalLifecycle(String subtype, String content,
                                               Map<String, Object> metadata) {
        if (sealed) return;
        try {
            appendLifecycle(subtype, content, metadata);
        } finally {
            sealed = true;
            pendingTools.clear();
        }
    }

    /** Silently rejects callbacks that arrive after an enclosing execution has terminated. */
    public synchronized void sealAgainstLateCallbacks() {
        sealed = true;
        pendingTools.clear();
    }

    private void appendLifecycle(String subtype, String content, Map<String, Object> metadata) {
        Map<String, Object> lifecycle = new LinkedHashMap<>(metadata != null ? metadata : Map.of());
        lifecycle.put("lifecycle_subtype", subtype);
        Map<String, Object> extra = traceMetadata(lifecycle);
        repository.append(conversationId, new AiChatTrajectoryStep(
                requestId, "agent", "agent_lifecycle", "debug", content, null,
                modelName, reasoningEffort,
                null, null, null, extra, 0, null, Instant.now()));
        emit(AiExecutionEvent.detail(subtype, content, extra));
    }

    /** Persists user-facing model narration as a normal chat row, not a progress pill. */
    public synchronized boolean guide(String content, Map<String, Object> metadata) {
        return appendGuide(content, metadata, true);
    }

    private boolean appendGuide(String content, Map<String, Object> metadata,
                                boolean deduplicateAdjacent) {
        if (sealed || !StringUtils.hasText(content)) return false;
        String stripped = content.strip();
        // A composed plan and its sole direct leaf legitimately carry the same guide
        // text; the user should read it once, not once per layer.
        if (deduplicateAdjacent && stripped.equals(lastGuideContent)) return false;
        lastGuideContent = stripped;
        Map<String, Object> extra = traceMetadata(metadata);
        repository.append(conversationId, new AiChatTrajectoryStep(
                requestId, "agent", "guide", "visible", stripped, null,
                modelName, reasoningEffort,
                null, null, null, extra, 0, null, Instant.now()));
        emit(AiExecutionEvent.detail("guide", stripped, extra));
        return true;
    }

    /** Selects the deterministic retry fallback language from the current user turn. */
    public synchronized void usePromptLanguage(String prompt) {
        retryMessageLanguage = AiToolRetryMessage.languageOf(prompt);
    }

    /** A new planner iteration legitimately re-narrates identical objectives. */
    public synchronized void resetGuideDeduplication() {
        lastGuideContent = null;
    }

    /**
     * Narrates one transient provider failure and the wait before the next attempt,
     * so the user watches the recovery instead of a silent stall. The step is
     * diagnostic; the live event drives the frontend's reconnecting countdown.
     */
    public synchronized void providerRetry(int attempt, int maxAttempts, long delayMillis,
                                           String reason, String failureClass, int statusCode) {
        if (sealed) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attempt", attempt);
        metadata.put("max_attempts", maxAttempts);
        metadata.put("delay_millis", delayMillis);
        if (StringUtils.hasText(reason)) {
            metadata.put("reason", reason);
        }
        if (StringUtils.hasText(failureClass)) {
            metadata.put("failure_class", failureClass);
        }
        if (statusCode > 0) {
            metadata.put("status_code", statusCode);
        }
        Map<String, Object> extra = traceMetadata(metadata);
        String content = "The model provider request failed; retrying (attempt "
                + attempt + " of " + maxAttempts + ").";
        repository.append(conversationId, new AiChatTrajectoryStep(
                requestId, "system", "provider_retry", "debug", content, null,
                modelName, reasoningEffort,
                null, null, null, extra, 0, null, Instant.now()));
        emit(AiExecutionEvent.detail("provider_retry", content, extra));
    }

    public synchronized void progress(String content) {
        if (sealed || !StringUtils.hasText(content)) {
            return;
        }
        repository.append(conversationId, new AiChatTrajectoryStep(
                requestId, "system", "progress", "debug", content, null, null,
                null, null, null, traceMetadata(
                        Map.of("event_sequence", eventSequence.incrementAndGet())),
                0, null, Instant.now()));
        emit(AiExecutionEvent.progress(content));
    }

    /** Emits streamed visible content without persisting partial duplicates. */
    public synchronized void contentDelta(String content) {
        if (!sealed && content != null && !content.isEmpty()) {
            emit(AiExecutionEvent.contentDelta(content));
        }
    }

    public synchronized void mutationConfirmationRequired(AiMutationConfirmationNotice notice) {
        if (sealed || notice == null) {
            return;
        }
        emit(AiExecutionEvent.detail("mutation_confirmation_required",
                "A data-changing action requires explicit approval.", Map.of(
                        "confirmationRequestId", notice.confirmationRequestId(),
                        "status", notice.status(),
                        "expiresAt", notice.expiresAt().toString(),
                        "toolName", notice.toolName(),
                        "argumentsSummary", notice.argumentsSummary())));
    }

    /** Emits one root-facing interaction for all approvals at the current execution barrier. */
    public synchronized void mutationApprovalBatchRequired(AiMutationApprovalBatchNotice notice) {
        if (sealed || notice == null) {
            return;
        }
        List<Map<String, Object>> items = notice.items().stream()
                .map(item -> {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("confirmationRequestId", item.confirmationRequestId());
                    metadata.put("toolName", item.toolName());
                    metadata.put("argumentsSummary", item.argumentsSummary());
                    if (StringUtils.hasText(item.agentId())) metadata.put("agentId", item.agentId());
                    if (StringUtils.hasText(item.agentLabel())) metadata.put("agentLabel", item.agentLabel());
                    return Map.copyOf(metadata);
                })
                .toList();
        emit(AiExecutionEvent.detail("mutation_approval_batch_required",
                items.size() == 1
                        ? "A data-changing action requires explicit approval."
                        : items.size() + " data-changing actions require explicit approval.",
                Map.of("batchId", notice.batchId(),
                        "expiresAt", notice.expiresAt().toString(),
                        "parallel", notice.parallel(),
                        "items", items)));
    }

    /** Emits the committed user decision before any retained worker can resume. */
    public synchronized void mutationApprovalDecisionAccepted(
            AiMutationApprovalCoordinator.DecisionAcknowledgement acknowledgement) {
        if (sealed || acknowledgement == null) {
            return;
        }
        long approved = acknowledgement.approved();
        long denied = acknowledgement.denied();
        emit(AiExecutionEvent.detail("mutation_approval_decision_accepted",
                "Approved " + approved + " action" + (approved == 1 ? "" : "s")
                        + " and denied " + denied + ". Continuing the active request.",
                Map.of("batchId", acknowledgement.batchId(),
                        "approved", approved, "denied", denied)));
    }

    public synchronized void elicitationRequired(AiElicitationNotice notice) {
        if (sealed || notice == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("elicitationId", notice.elicitationId());
        metadata.put("expiresAt", notice.expiresAt().toString());
        metadata.put("mode", "form");
        metadata.put("message", notice.message());
        metadata.put("requestedSchema", notice.requestedSchema());
        emit(AiExecutionEvent.detail("elicitation_required",
                "The assistant needs your input before it can continue.", metadata));
    }

    public synchronized void recordModelResponse(ChatResponse response, String phase) {
        recordModelResponse(response, phase, false);
    }

    /** Records a response aggregated from streaming chunks with cache metadata loss in mind. */
    public synchronized void recordStreamingModelResponse(ChatResponse response, String phase) {
        recordModelResponse(response, phase, true);
    }

    private void recordModelResponse(ChatResponse response, String phase, boolean streaming) {
        if (sealed || response == null || response.getResults().isEmpty()) {
            return;
        }
        String normalizedPhase = StringUtils.hasText(phase) ? phase : "model";
        boolean reasoningPresent = StringUtils.hasText(reasoning(response.getResults()));
        String message = visibleMessage(response.getResults());
        List<Map<String, Object>> toolCalls = toolCalls(response.getResults());
        List<Map<String, Object>> auditedToolCalls = auditToolCalls(toolCalls);
        AiMetricsSnapshot metricsSnapshot = metrics(response, streaming);
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
        if (StringUtils.hasText(message)) {
            // Raw model text is a private candidate until Agent Output Guardrails
            // accept or rewrite it. The final safe answer is persisted by ChatService.
            extra.put("candidate_content_suppressed", true);
        }
        extra = new LinkedHashMap<>(traceMetadata(extra));

        AiChatStoredStep stored = repository.append(conversationId,
                new AiChatTrajectoryStep(requestId, "agent", "model_call", "debug",
                        "", null,
                        StringUtils.hasText(modelName) ? modelName : response.getMetadata().getModel(),
                        reasoningEffort,
                        auditedToolCalls, null, metrics, extra, 1, null, Instant.now()));

        AiObservationAccumulator observations = new AiObservationAccumulator(stored.id(), toolCalls);
        for (Map<String, Object> call : toolCalls) {
            String name = Objects.toString(call.get("function_name"), "tool");
            String callId = Objects.toString(call.get("tool_call_id"), UUID.randomUUID().toString());
            pendingTools.computeIfAbsent(name, ignored -> new ConcurrentLinkedQueue<>())
                    .add(new AiPendingTool(callId, name, call.get("arguments"), observations,
                            toolSequence.getAndIncrement()));
        }
        if (metricsSnapshot != null) {
            ownModelCalls.incrementAndGet();
            ownPromptTokens.addAndGet(longMetric(metricsSnapshot.metrics().get("prompt_tokens")));
            ownCompletionTokens.addAndGet(longMetric(metricsSnapshot.metrics().get("completion_tokens")));
        }
        // Subagent-scoped calls include transient fan-out prompts; only the container's
        // settled aggregate may drive the conversation's visible context usage.
        if (metricsSnapshot != null && contextBudget != null && !subagentScope) {
            emitContextUsage(contextBudget.usage(metricsSnapshot.contextInputTokens(),
                    metricsSnapshot.estimated(), metricsSnapshot.estimated() ? "estimate_floor" : "provider"));
        }
    }

    private long longMetric(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private boolean delegatedWorkerScope() {
        return conversationKind != AiChatConversationKind.ROOT
                || traceContext.containsKey("agent_id")
                || Boolean.TRUE.equals(traceContext.get("concurrent_branch"));
    }

    public synchronized void recordToolResponses(List<Message> messages) {
        if (sealed || messages == null) {
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
                AiPendingTool pending = pendingTools.computeIfAbsent(response.name(), ignored -> new ConcurrentLinkedQueue<>())
                        .poll();
                if (pending == null) {
                    pending = new AiPendingTool(response.id(), response.name(), Map.of(),
                            new AiObservationAccumulator(0L, List.of()), toolSequence.getAndIncrement());
                }
                toolStarted(pending);
                toolCompleted(pending, response.responseData(), null, Duration.ZERO);
            }
        }
    }

    /**
     * Number of tool calls this recorder has observed to completion. Callers use
     * changes in this count as segment boundaries in the visible answer stream:
     * narration emitted before a tool call is interim commentary, not the answer.
     */
    public long completedToolCallCount() {
        return completedToolCalls.get();
    }

    /** Number of completed connectCenter calls, excluding the tool-discovery helper. */
    public long completedDomainToolCallCount() {
        return completedDomainToolCalls.get();
    }

    /** Number of successful connectCenter calls local to this recorder, excluding tool discovery. */
    public long successfulDomainToolCallCount() {
        return successfulDomainToolCalls.get();
    }

    /**
     * Request-wide count of connectCenter domain tool calls that executed successfully,
     * shared across every forked worker and lead recorder. Guard-intercepted and failed
     * calls are excluded: this is the evaluator's grounding evidence, not a boundary marker.
     */
    public long executedDomainToolCallCount() {
        return requestExecutedDomainToolCalls.get();
    }

    /** Request-wide count of data-changing calls intercepted and awaiting user approval. */
    public long pendingApprovalCount() {
        return requestPendingApprovalIds.size();
    }

    /** Removes the exact intercepted approvals from request-wide evaluator evidence. */
    public void mutationApprovalsResolved(List<AiPendingMutationApproval> approvals) {
        if (approvals == null || approvals.isEmpty()) {
            return;
        }
        approvals.forEach(approval -> {
            requestPendingApprovalIds.remove(approval.notice().confirmationRequestId());
            requestPendingApprovalIds.remove(pendingApprovalToolIdentity(
                    approval.toolName(), arguments(approval.arguments())));
        });
    }

    /**
     * Tool calls this recorder observed that actually ran a non-read-only tool.
     * A model attempt whose count moved must never be replayed by the provider
     * retry loop: re-running it could repeat the data change.
     */
    public long executedMutationToolCallCount() {
        return executedMutationToolCalls.get();
    }

    /**
     * Declares the MCP-session tools the server annotated read-only so every recorded
     * tool step carries the guard classification external verifiers evaluate against.
     */
    public void readOnlyToolNames(Set<String> names) {
        this.readOnlyToolNames = names != null ? Set.copyOf(names) : Set.of();
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
        AiBoundedToolOutput bounded = reserveToolOutput(output,
                toolOutputTokenLimit > 0 ? toolOutputTokenLimit : Long.MAX_VALUE);
        emitToolOutputTruncated(bounded, toolOutputTokenLimit, toolName);
        emitToolOutputUsage(bounded);
        return bounded.value();
    }

    public synchronized void contextCompacted(String reason, long beforeTokens,
                                              AiContextUsageInfo usage, boolean automatic) {
        if (sealed) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", StringUtils.hasText(reason) ? reason : "threshold");
        metadata.put("automatic", automatic);
        metadata.put("beforeInputTokens", Math.max(0L, beforeTokens));
        if (usage != null) metadata.put("contextUsage", usage);
        emit(AiExecutionEvent.detail("context_compacted",
                automatic ? "Conversation context was compacted automatically."
                        : "Conversation context was compacted.", metadata));
    }

    public synchronized void contextUsage(AiContextUsageInfo usage) {
        if (!sealed && usage != null) emitContextUsage(usage);
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

    private AiMetricsSnapshot metrics(ChatResponse response, boolean streaming) {
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        PromptTokenSnapshot prompt = promptTokenAccounting.resolve(usage, streaming);
        long contextInputTokens = Math.max(prompt.atifPromptTokens(), estimatedInputFloor.get());
        boolean contextEstimated = !prompt.complete()
                || contextInputTokens > prompt.atifPromptTokens();
        if (!prompt.complete()) {
            // Spring AI's Anthropic streaming adapter can lose cache usage reported on
            // message_start. ATIF prompt_tokens must include that cached prefix, so the
            // incomplete provider value is retained only as diagnostic metadata.
            metrics.put("provider_reported_prompt_tokens", prompt.providerReportedTokens());
            metrics.put("prompt_tokens_complete", false);
            metrics.put("prompt_token_accounting", promptTokenAccounting.wireValue);
        } else {
            metrics.put("prompt_tokens", prompt.atifPromptTokens());
            metrics.put("prompt_tokens_complete", true);
            metrics.put("prompt_token_accounting", promptTokenAccounting.wireValue);
        }
        putIfPresent(metrics, "completion_tokens", usage.getCompletionTokens());
        putIfPresent(metrics, "cached_tokens", usage.getCacheReadInputTokens());
        if (usage.getCacheWriteInputTokens() != null) {
            metrics.put("extra", Map.of("cache_creation_input_tokens", usage.getCacheWriteInputTokens()));
        }
        estimatedInputFloor.accumulateAndGet(contextInputTokens, Math::max);
        metrics.put("context_input_tokens", contextInputTokens);
        metrics.put("context_estimated", contextEstimated);
        if (subagentScope) {
            // Marks the row so the conversation's latest-usage lookup skips it.
            metrics.put("context_scope", "subagent");
        }
        return new AiMetricsSnapshot(Map.copyOf(metrics), contextInputTokens, contextEstimated);
    }

    /**
     * Spring AI exposes provider-native prompt usage: Anthropic reports cache reads and
     * writes outside {@code input_tokens}, while OpenAI-compatible providers report cached
     * input as a subset of their prompt total. ATIF always requires the inclusive total.
     */
    private enum PromptTokenAccounting {
        CACHE_EXCLUDED("cache_excluded"),
        CACHE_INCLUDED("cache_included"),
        UNKNOWN("unknown");

        private final String wireValue;

        PromptTokenAccounting(String wireValue) {
            this.wireValue = wireValue;
        }

        private static PromptTokenAccounting fromProviderType(String providerType) {
            if (!StringUtils.hasText(providerType)) {
                return UNKNOWN;
            }
            return switch (providerType.strip().toLowerCase()) {
                case "anthropic" -> CACHE_EXCLUDED;
                case "openai", "azure-openai" -> CACHE_INCLUDED;
                default -> UNKNOWN;
            };
        }

        private PromptTokenSnapshot resolve(Usage usage, boolean streaming) {
            Number reported = usage.getPromptTokens();
            long providerTokens = reported != null ? reported.longValue() : 0L;
            long cacheReadTokens = Objects.requireNonNullElse(
                    usage.getCacheReadInputTokens(), 0L);
            long cacheWriteTokens = Objects.requireNonNullElse(
                    usage.getCacheWriteInputTokens(), 0L);
            long cacheTokens = cacheReadTokens + cacheWriteTokens;
            long inclusiveTokens = this == CACHE_INCLUDED
                    ? providerTokens : providerTokens + cacheTokens;
            boolean missingStreamingCacheUsage = this == CACHE_EXCLUDED && streaming
                    && usage.getCacheReadInputTokens() == null
                    && usage.getCacheWriteInputTokens() == null;
            boolean complete = reported != null
                    && this != UNKNOWN
                    && !missingStreamingCacheUsage;
            return new PromptTokenSnapshot(providerTokens, inclusiveTokens, complete);
        }
    }

    private record PromptTokenSnapshot(long providerReportedTokens,
                                       long atifPromptTokens,
                                       boolean complete) {}

    private void emitContextUsage(AiContextUsageInfo usage) {
        emit(AiExecutionEvent.detail("context_usage", "Context usage updated.",
                Map.of("contextUsage", usage)));
    }

    private AiPendingTool pending(String toolName, String input) {
        ConcurrentLinkedQueue<AiPendingTool> queue = pendingTools.get(toolName);
        Object parsedArguments = arguments(input);
        AiPendingTool pending = null;
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
        return new AiPendingTool(UUID.randomUUID().toString(), toolName, parsedArguments,
                new AiObservationAccumulator(0L, List.of()), toolSequence.getAndIncrement());
    }

    private synchronized void toolStarted(AiPendingTool pending) {
        if (sealed) return;
        boolean retryAlreadyNarrated = narratedToolCallIds.remove(pending.id())
                || delegatedWorkerScope();
        toolRetryTracker.retry(pending, retryAlreadyNarrated)
                .ifPresent(notice -> appendGuide(
                        AiToolRetryMessage.format(notice, retryMessageLanguage),
                        Map.of("phase", "assistant", "tool_retry", true,
                                "tool_name", pending.name()),
                        false));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tool_call_id", pending.id());
        extra.put("tool_name", pending.name());
        extra.put("tool_status", "started");
        extra.put("read_only", readOnlyToolNames.contains(pending.name()));
        extra.put("tool_call_sequence", pending.sequence());
        extra.put("arguments", boundedRedactedValue(pending.arguments()));
        repository.append(conversationId, new AiChatTrajectoryStep(
                requestId, "agent", "tool_call_update", "debug",
                "Calling " + pending.name() + ".", null, modelName,
                reasoningEffort,
                null, null, null, traceMetadata(extra), 0, null, Instant.now()));
        emit(AiExecutionEvent.tool("started", "Calling " + pending.name() + ".",
                pending.id(), pending.name(), pending.sequence(),
                toolObservationMetadata(pending.name())));
    }

    private synchronized void toolCompleted(AiPendingTool pending, String output,
                                            Throwable failure, Duration duration) {
        toolCompleted(pending, output, failure, duration, false);
    }

    private synchronized void toolCompleted(AiPendingTool pending, String output,
                                            Throwable failure, Duration duration,
                                            boolean resultTruncated) {
        if (sealed) return;
        if (!completedToolCallIds.add(pending.id())) {
            return;
        }
        String approvalIdentity = failure == null
                ? pendingApprovalIdentity(output, pending) : null;
        String status = failure != null ? "failed"
                : approvalIdentity != null ? "blocked"
                : deniedBeforeExecution(output) ? "denied"
                : stoppedBeforeExecution(output) ? "cancelled" : "completed";
        boolean successful = "completed".equals(status);
        if (failure != null) {
            toolRetryTracker.failed(pending);
        }
        completedToolCalls.incrementAndGet();
        if (!"toolSearchTool".equals(pending.name())) {
            completedDomainToolCalls.incrementAndGet();
            if ("completed".equals(status)) {
                successfulDomainToolCalls.incrementAndGet();
                requestExecutedDomainToolCalls.incrementAndGet();
            }
        }
        if ("blocked".equals(status)) {
            requestPendingApprovalIds.add(approvalIdentity);
        }
        // Guard-intercepted calls never executed; completed or failed calls on a
        // tool without the read-only annotation may have changed data.
        if (("completed".equals(status) || "failed".equals(status))
                && !"toolSearchTool".equals(pending.name())
                && !readOnlyToolNames.contains(pending.name())) {
            executedMutationToolCalls.incrementAndGet();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_call_id", pending.id());
        result.put("content", auditText(output));
        result.put("extra", Map.of(
                "duration_ms", duration.toMillis(),
                "success", successful));
        pending.observations().results().put(pending.id(), result);
        if (pending.observations().stepId() > 0) {
            repository.updateObservation(conversationId, pending.observations().stepId(),
                    Map.of("results", pending.observations().orderedResults()));
        }

        String detail = toolDetail(pending, output, failure);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tool_call_id", pending.id());
        extra.put("tool_name", pending.name());
        extra.put("tool_status", status);
        extra.put("read_only", readOnlyToolNames.contains(pending.name()));
        extra.put("tool_call_sequence", pending.sequence());
        extra.put("arguments", boundedRedactedValue(pending.arguments()));
        extra.put("duration_ms", duration.toMillis());
        extra.put("success", successful);
        extra.put("result_truncated", resultTruncated);
        if (failure != null) extra.put("failure_type", failure.getClass().getName());
        extra = new LinkedHashMap<>(traceMetadata(extra));
        repository.append(conversationId, new AiChatTrajectoryStep(
                requestId, "agent", "tool_call", "debug", detail, null, modelName,
                reasoningEffort,
                null, null, null, extra, 0, null, Instant.now()));
        Map<String, Object> eventMetadata = new LinkedHashMap<>();
        eventMetadata.put("toolDetail", detail);
        eventMetadata.put("duration_ms", duration.toMillis());
        eventMetadata.put("success", successful);
        eventMetadata.put("result_truncated", resultTruncated);
        eventMetadata.put("read_only", readOnlyToolNames.contains(pending.name()));
        eventMetadata.put("mcp", mcpToolNames.contains(pending.name()));
        if (mcpToolNames.contains(pending.name())) {
            eventMetadata.putAll(mcpObservationMetadata());
        }
        if (failure != null) eventMetadata.put("failure_type", failure.getClass().getName());
        emit(AiExecutionEvent.tool(status,
                switch (status) {
                    case "failed" -> pending.name() + " failed.";
                    case "blocked" -> pending.name() + " is awaiting approval.";
                    case "denied" -> pending.name() + " was denied before execution.";
                    case "cancelled" -> pending.name() + " was stopped before execution.";
                    default -> pending.name() + " completed.";
                },
                pending.id(), pending.name(), pending.sequence(), Map.copyOf(eventMetadata)));
    }

    /** Returns the stable confirmation identity embedded by the mutation guard. */
    private String pendingApprovalIdentity(String output, AiPendingTool pending) {
        if (!StringUtils.hasText(output)) {
            return null;
        }
        try {
            var root = objectMapper.readTree(output);
            var error = root != null && root.isObject() ? root.get("error") : null;
            if (error == null || !error.isTextual()
                    || !AiMutationToolGuard.MUTATION_CONFIRMATION_REQUIRED.equals(
                    error.textValue())) {
                return null;
            }
            var confirmationId = root.get("confirmationRequestId");
            return confirmationId != null && confirmationId.isTextual()
                    && StringUtils.hasText(confirmationId.textValue())
                    ? confirmationId.textValue()
                    : pendingApprovalToolIdentity(pending.name(), pending.arguments());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String pendingApprovalToolIdentity(String toolName, Object arguments) {
        return "tool:" + Objects.toString(toolName, "") + "\u0000" + toJson(arguments);
    }

    /** The guard declines new data changes while the user is stopping the request. */
    private boolean stoppedBeforeExecution(String output) {
        return hasTopLevelError(output, AiMutationToolGuard.REQUEST_STOPPING);
    }

    /** The guard returns a stable cached result when an exact mutation was denied. */
    private boolean deniedBeforeExecution(String output) {
        return hasTopLevelError(output, AiMutationToolGuard.MUTATION_CONFIRMATION_DENIED);
    }

    private boolean hasTopLevelError(String output, String expected) {
        if (!StringUtils.hasText(output)) {
            return false;
        }
        try {
            var root = objectMapper.readTree(output);
            var error = root != null && root.isObject() ? root.get("error") : null;
            return error != null && error.isTextual() && expected.equals(error.textValue());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String toolDetail(AiPendingTool pending, String output, Throwable failure) {
        StringBuilder detail = new StringBuilder(pending.name())
                .append("\nArguments: ").append(toJson(boundedRedactedValue(pending.arguments())));
        if (failure == null) {
            detail.append("\nResult: ").append(auditText(output));
        } else {
            detail.append("\nError: ").append(AiToolFailureMessage.userMessage(failure));
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
            AiPendingTool pending = pending(getToolDefinition().name(), input);
            toolStarted(pending);
            Instant started = Instant.now();
            try (var ignored = observationContext.makeToolCurrent(requestId, pending.id())) {
                String output = delegate.call(input, context);
                AiBoundedToolOutput bounded = reserveToolOutput(output, toolOutputTokenLimit);
                emitToolOutputTruncated(bounded, toolOutputTokenLimit, pending.name());
                emitToolOutputUsage(bounded);
                toolCompleted(pending, output, null, Duration.between(started, Instant.now()),
                        bounded.truncated());
                return bounded.value();
            } catch (RuntimeException exception) {
                LOGGER.warn("AI tool {} failed for request {}", pending.name(), requestId, exception);
                toolCompleted(pending, null, exception, Duration.between(started, Instant.now()));
                throw exception;
            }
        }
    }

    private AiBoundedToolOutput boundedToolOutput(String output, long tokenLimit) {
        String source = Objects.requireNonNullElse(output, "");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        long byteLimit = tokenLimit == Long.MAX_VALUE || tokenLimit > Integer.MAX_VALUE / 3L
                ? Integer.MAX_VALUE : Math.max(0L, tokenLimit) * 3L;
        if (bytes.length <= byteLimit) {
            return new AiBoundedToolOutput(source, false, bytes.length, bytes.length);
        }
        int maximumBytes = (int) byteLimit;
        String suffix = "\n[TOOL OUTPUT TRUNCATED: rerun the tool with narrower filters or pagination.]";
        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        if (maximumBytes <= suffixBytes) {
            String marker = suffix.substring(0, Math.min(maximumBytes, suffix.length()));
            return new AiBoundedToolOutput(marker, true, bytes.length,
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
        return new AiBoundedToolOutput(bounded, true, bytes.length, returnedBytes);
    }

    private synchronized AiBoundedToolOutput reserveToolOutput(String output, long configuredLimit) {
        long effectiveLimit = configuredLimit;
        if (contextBudget != null) {
            long remaining = Math.max(0L, contextBudget.safeInputLimit() - estimatedInputFloor.get());
            effectiveLimit = Math.min(effectiveLimit, remaining);
        }
        AiBoundedToolOutput bounded = boundedToolOutput(output, effectiveLimit);
        growEstimatedInputFloor(bounded.returnedBytes());
        return bounded;
    }

    private void emitToolOutputTruncated(AiBoundedToolOutput bounded, long configuredLimit,
                                         String toolName) {
        if (!bounded.truncated()) return;
        String safeToolName = StringUtils.hasText(toolName) ? toolName : "tool";
        emit(AiExecutionEvent.detail("tool_output_truncated",
                safeToolName + " returned more data than the active context budget allows.", Map.of(
                        "toolName", safeToolName,
                        "mcp", mcpToolNames.contains(safeToolName),
                        "originalUtf8Bytes", bounded.originalBytes(),
                        "returnedUtf8Bytes", bounded.returnedBytes(),
                        "toolOutputTokenLimit", configuredLimit)));
    }

    private Map<String, Object> toolObservationMetadata(String toolName) {
        if (!mcpToolNames.contains(toolName)) return Map.of("mcp", false);
        return Map.copyOf(mcpObservationMetadata());
    }

    private Map<String, Object> mcpObservationMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mcp", true);
        metadata.put("mcp_server_name", mcpServerName);
        if (mcpProtocolVersion != null) metadata.put("mcp_protocol_version", mcpProtocolVersion);
        if (mcpServerAddress != null) metadata.put("server_address", mcpServerAddress);
        if (mcpServerPort > 0) metadata.put("server_port", mcpServerPort);
        if (mcpNetworkProtocolName != null) {
            metadata.put("network_protocol_name", mcpNetworkProtocolName);
        }
        if (mcpNetworkTransport != null) metadata.put("network_transport", mcpNetworkTransport);
        return metadata;
    }

    private void emitToolOutputUsage(AiBoundedToolOutput bounded) {
        // Subagent tool outputs grow only the child's floor; the conversation's
        // visible context usage is settled by the container on fan-out completion.
        if (contextBudget == null || bounded.returnedBytes() <= 0 || subagentScope) return;
        emitContextUsage(contextBudget.usage(estimatedInputFloor.get(), true,
                "tool_output_estimate"));
    }

    private void growEstimatedInputFloor(int utf8Bytes) {
        long additional = utf8Bytes <= 0 ? 0L : (utf8Bytes + 2L) / 3L;
        estimatedInputFloor.updateAndGet(current -> current > Long.MAX_VALUE - additional
                ? Long.MAX_VALUE : current + additional);
    }

    private Map<String, Object> traceMetadata(Map<String, Object> metadata) {
        if (traceContext.isEmpty() && (metadata == null || metadata.isEmpty())) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null) merged.putAll(metadata);
        // The server-owned namespace wins over provider/tool metadata.
        merged.putAll(traceContext);
        return Map.copyOf(merged);
    }

    private synchronized void emit(AiExecutionEvent event) {
        if (sealed) return;
        AiExecutionEvent realtimeEvent = new AiExecutionEvent(
                event.type(), event.subtype(), event.content(),
                event.toolCallId(), event.toolName(), event.toolCallSequence(),
                realtimeMetadata(event.metadata()));
        if (executionScope != null) {
            try {
                observer.observe(AiExecutionLifecycle.from(realtimeEvent).observation(executionScope));
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not observe AI trajectory event {} for request {}",
                        event.subtype(), requestId, failure);
            }
        }
        try {
            realtimeEvents.accept(realtimeEvent);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not deliver AI trajectory event {} for request {}",
                    event.subtype(), requestId, failure);
        }
    }

    /** Keeps persisted ATIF metadata stable while exposing frontend-friendly trace aliases. */
    private Map<String, Object> realtimeMetadata(Map<String, Object> metadata) {
        Map<String, Object> merged = new LinkedHashMap<>(traceMetadata(metadata));
        alias(merged, "fanout_id", "fanoutId");
        alias(merged, "node_id", "nodeId");
        alias(merged, "node_id", "agentId");
        alias(merged, "parent_node_id", "parentNodeId");
        alias(merged, "agent_name", "agentName");
        alias(merged, "agent_role", "agentRole");
        alias(merged, "task_label", "taskLabel");
        alias(merged, "active_verb", "activeVerb");
        alias(merged, "completed_verb", "completedVerb");
        alias(merged, "execution_scope", "executionScope");
        alias(merged, "child_conversation_id", "childConversationId");
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private void alias(Map<String, Object> metadata, String source, String target) {
        if (metadata.containsKey(source) && !metadata.containsKey(target)) {
            metadata.put(target, metadata.get(source));
        }
    }

}

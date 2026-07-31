package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.model.AiBoundedToolOutput;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiMetricsSnapshot;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiObservationAccumulator;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingTool;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeToolGuard;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolFailureMessage;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolRetryMessage;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolRetryTracker;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Records ATIF-compatible execution history and publishes content-free lifecycle facts
 * through {@link ExecutionObserver} for independent consumers such as OpenTelemetry.
 */
public final class AiTrajectoryRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTrajectoryRecorder.class);
    public static final String PHASE_CONTEXT_KEY = "score.ai.trajectory.phase";
    /** Stable trajectory wire value recognized by external verifiers. */
    public static final String FANOUT_USAGE_STEP_KIND = "fanout_usage";

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
    private final AiTrajectoryEventWriter eventWriter;
    private final AiTrajectoryAuditSanitizer auditSanitizer;
    private final AiTrajectoryModelCalls modelCalls;
    private final AiToolOutputLimiter toolOutputLimiter;
    private final AtomicLong estimatedInputFloor;
    private final AtomicLong eventSequence;
    private final AtomicLong toolSequence;
    private final Map<String, Object> traceContext;
    private final AiChatConversationKind conversationKind;
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
    private final AtomicLong executedChangeToolCalls = new AtomicLong();
    private volatile Set<String> readOnlyToolNames = Set.of();
    private volatile boolean sealed;
    private volatile boolean usageAccountingSealed;
    private volatile String lastGuideContent;
    private final ThreadLocal<java.util.ArrayDeque<String>> activeAgentRuns =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

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
                estimatedInputFloor, ProviderPromptTokenNormalizer.forProvider(null),
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
                                 ProviderPromptTokenNormalizer promptTokenNormalizer,
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
        this.reasoningEffort = reasoningEffort;
        this.realtimeEvents = events != null ? events : ignored -> {};
        this.contextBudget = contextBudget;
        this.executionScope = executionScope;
        this.observer = observer != null ? observer : ExecutionObserver.noop();
        this.observationContext = observationContext != null
                ? observationContext : ExecutionObservationContext.noop();
        this.estimatedInputFloor = new AtomicLong(Math.max(0L, estimatedInputFloor));
        this.eventSequence = eventSequence;
        this.toolSequence = toolSequence;
        this.requestExecutedDomainToolCalls = requestExecutedDomainToolCalls;
        this.requestPendingApprovalIds = requestPendingApprovalIds;
        this.traceContext = traceContext != null ? Map.copyOf(traceContext) : Map.of();
        this.conversationKind = conversationKind;
        this.eventWriter = new AiTrajectoryEventWriter(repository, conversationId,
                requestId, this.realtimeEvents, executionScope, this.observer,
                this.traceContext, this::activeAgentRunId, () -> sealed);
        this.auditSanitizer = new AiTrajectoryAuditSanitizer(objectMapper);
        this.toolOutputLimiter = new AiToolOutputLimiter(
                contextBudget, this.estimatedInputFloor, subagentScope);
        this.modelCalls = new AiTrajectoryModelCalls(
                repository, objectMapper, conversationId, requestId, modelName, reasoningEffort,
                contextBudget, this.estimatedInputFloor, promptTokenNormalizer, executionScope,
                this.observer, eventWriter, auditSanitizer::boundedValue,
                (name, callId, arguments, observations) -> pendingTools
                        .computeIfAbsent(name, ignored -> new ConcurrentLinkedQueue<>())
                        .add(new AiPendingTool(callId, name, arguments, observations,
                                toolSequence.getAndIncrement())),
                this::emitContextUsage, () -> sealed, () -> usageAccountingSealed,
                subagentScope, this.traceContext);
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
                estimatedInputFloor.get(), modelCalls.promptTokenNormalizer(), eventSequence, toolSequence,
                requestExecutedDomainToolCalls, requestPendingApprovalIds, childContext,
                executionScope, observer, observationContext, true, conversationKind);
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
                estimatedInputFloor.get(), modelCalls.promptTokenNormalizer(), eventSequence, toolSequence,
                requestExecutedDomainToolCalls, requestPendingApprovalIds,
                traceMetadata(childNamespace), childExecutionScope(childConversationId),
                observer, observationContext,
                true, kind);
        child.persistWithoutRealtime(new AiChatTrajectoryStep(
                requestId, "system", "settings_change", "debug",
                "Child execution settings initialized.", null, modelName, reasoningEffort,
                null, null, null,
                child.traceMetadata(Map.of("agent_id", workerId)), 0, null, null),
                AiExecutionEvent.detail("child_settings_recorded", "", Map.of()));
        child.persistWithoutRealtime(new AiChatTrajectoryStep(
                requestId, "user",
                kind == AiChatConversationKind.PARALLEL ? "parallel_assignment" : "assignment",
                "visible",
                Objects.requireNonNullElse(assignment, ""), null, modelName, reasoningEffort,
                null, null, null,
                child.traceMetadata(Map.of("copied_from_parent", true)), 0, true, null),
                AiExecutionEvent.detail("child_assignment_recorded", "", Map.of()));
        return child;
    }

    private ExecutionScope childExecutionScope(String childConversationId) {
        if (executionScope == null) return null;
        return new ExecutionScope(executionScope.requestId(), childConversationId,
                executionScope.requesterId(), executionScope.generation(),
                ExecutionScope.Purpose.WORKER, executionScope.guardrailDecisionIds());
    }

    public String conversationId() {
        return conversationId;
    }

    public AiChatConversationKind conversationKind() {
        return conversationKind;
    }

    public String requestId() { return requestId; }
    public String modelName() { return modelName; }
    public String requestModelName() { return modelCalls.requestModelName(); }
    public String modelProvider() { return modelCalls.provider(); }
    public long executionGeneration() {
        return executionScope != null ? executionScope.generation() : 0L;
    }
    public String activeAgentRunId() { return activeAgentRuns.get().peek(); }

    public AgentRunActivation activateAgentRun(String runId) {
        if (!StringUtils.hasText(runId)) return AgentRunActivation.noop();
        java.util.ArrayDeque<String> runs = activeAgentRuns.get();
        runs.push(runId.strip());
        return () -> {
            java.util.ArrayDeque<String> active = activeAgentRuns.get();
            if (!active.isEmpty()) active.pop();
            if (active.isEmpty()) activeAgentRuns.remove();
        };
    }

    @FunctionalInterface
    public interface AgentRunActivation extends AutoCloseable {
        @Override
        void close();

        static AgentRunActivation noop() { return () -> { }; }
    }

    /** Records the accepted user turn without exposing its content to event listeners. */
    public synchronized void recordUserMessage(String content, Map<String, Object> metadata) {
        persistWithoutRealtime(new AiChatTrajectoryStep(
                        requestId, "user", "user", "visible", Objects.requireNonNullElse(content, ""),
                        null, modelName, reasoningEffort, null, null, null,
                        traceMetadata(metadata), null, null, null),
                AiExecutionEvent.detail("user_message_recorded", "", Map.of()));
    }

    /** Records the output-guarded assistant answer without exposing its content to listeners. */
    public synchronized void recordAssistantMessage(String content, Map<String, Object> metadata) {
        persistWithoutRealtime(new AiChatTrajectoryStep(
                        requestId, "agent", "assistant", "visible", Objects.requireNonNullElse(content, ""),
                        null, modelName, reasoningEffort, null, null, null,
                        traceMetadata(metadata), 0, null, null),
                AiExecutionEvent.detail("assistant_message_recorded", "", Map.of()));
    }

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
        modelCalls.useProvider(providerType);
    }

    /** Configures the exact model identifier sent to the provider for GenAI telemetry. */
    public void useModelProvider(String providerType, String requestModelName) {
        modelCalls.useProvider(providerType, requestModelName);
    }

    /** Snapshot of the model usage this recorder observed, keyed by its fan-out namespace. */
    public AiUsageSnapshot usageSnapshot() {
        return modelCalls.usageSnapshot();
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
        if (sealed) return;
        recordSettledFanOutUsage(fanoutId, executionKind, agents);
    }

    /** Accounting-only terminal write; it cannot disclose model or Tool content. */
    public synchronized void recordSettledFanOutUsage(
            String fanoutId, String executionKind, List<AiUsageSnapshot> agents) {
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
        persistWithoutRealtime(new AiChatTrajectoryStep(
                requestId, "system", FANOUT_USAGE_STEP_KIND, "debug",
                "parallel".equals(executionKind)
                        ? "Parallel workflow usage settled." : "Multi-agent fan-out usage settled.",
                null, modelName,
                reasoningEffort,
                null, null, Map.copyOf(metrics), traceMetadata(extra), 0, null, null),
                AiExecutionEvent.detail("fanout_usage_recorded", "", Map.of(
                        "fanout_id", Objects.requireNonNullElse(fanoutId, "unknown"))));
        if (contextBudget != null) {
            emitContextUsage(contextBudget.usage(estimatedInputFloor.get(), true, "fanout_settled"));
        }
    }

    /** Persists and emits one fan-out or specialist lifecycle transition. */
    public synchronized void lifecycle(String subtype, String content, Map<String, Object> metadata) {
        if (sealed) return;
        appendLifecycle(subtype, content, metadata);
    }

    /** Rejects provider callbacks that race with terminal request cleanup. */
    public synchronized void verifyActive() {
        if (sealed) {
            throw new CancellationException("The Agent execution has already terminated.");
        }
    }

    /** Runs a side-effecting callback under the same lock used by terminal sealing. */
    public synchronized <T> T callWhileActive(Supplier<T> action) {
        verifyActive();
        return Objects.requireNonNull(action, "action").get();
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

    /** Stops accounting-only updates after the bounded provider settlement window. */
    public synchronized void sealUsageAccounting() {
        usageAccountingSealed = true;
    }

    private void appendLifecycle(String subtype, String content, Map<String, Object> metadata) {
        Map<String, Object> lifecycle = new LinkedHashMap<>(metadata != null ? metadata : Map.of());
        lifecycle.put("lifecycle_subtype", subtype);
        Map<String, Object> extra = traceMetadata(lifecycle);
        persistAndEmit(new AiChatTrajectoryStep(
                requestId, "agent", "agent_lifecycle", "debug", content, null,
                modelName, reasoningEffort,
                null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail(subtype, content, extra));
    }

    /** Persists user-facing model narration as a normal chat row, not a progress pill. */
    public synchronized boolean guide(String content, Map<String, Object> metadata) {
        return appendGuide(content, metadata, true);
    }

    /** Persists and emits a policy-approved top-level Workflow synthesis. */
    public synchronized void workflowResult(AgentOutput output, Map<String, Object> metadata) {
        Objects.requireNonNull(output, "output");
        if (!output.passedOutputGuardrail(AgentOutputGuardrail.Scope.PUBLIC)) {
            throw new IllegalArgumentException(
                    "Workflow results require PUBLIC output-guardrail evidence.");
        }
        String content = output.content();
        if (sealed || !StringUtils.hasText(content)) return;
        String stripped = content.strip();
        Map<String, Object> extra = traceMetadata(metadata);
        persistAndEmit(new AiChatTrajectoryStep(
                requestId, "agent", "workflow_result", "visible", stripped, null,
                modelName, reasoningEffort,
                null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail("workflow_result", stripped, extra));
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
        persistAndEmit(new AiChatTrajectoryStep(
                requestId, "agent", "guide", "visible", stripped, null,
                modelName, reasoningEffort,
                null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail("guide", stripped, extra));
        return true;
    }

    /** A new planner iteration legitimately re-narrates identical objectives. */
    public synchronized void resetGuideDeduplication() {
        lastGuideContent = null;
    }

    /**
     * Publishes the provider's bounded error first, then the retry narration.
     * The wire keeps error and retry facts separate for audit and observability;
     * presentation clients may coalesce them into one transient recovery status.
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
        String errorContent = StringUtils.hasText(reason)
                ? reason.strip() : "The model provider could not complete the request.";
        persistAndEmit(new AiChatTrajectoryStep(
                requestId, "system", "provider_error", "debug", errorContent, null,
                modelName, reasoningEffort,
                null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail("provider_error", errorContent, extra));

        String retryContent = "The model provider request failed; retrying (attempt "
                + attempt + " of " + maxAttempts + ").";
        persistAndEmit(new AiChatTrajectoryStep(
                requestId, "system", "provider_retry", "debug", retryContent, null,
                modelName, reasoningEffort,
                null, null, null, extra, 0, null, null),
                AiExecutionEvent.detail("provider_retry", retryContent, extra));
    }

    public synchronized void progress(String content) {
        if (sealed || !StringUtils.hasText(content)) {
            return;
        }
        persistAndEmit(new AiChatTrajectoryStep(
                requestId, "system", "progress", "debug", content, null, null,
                null, null, null, traceMetadata(
                        Map.of("event_sequence", eventSequence.incrementAndGet())),
                0, null, null), AiExecutionEvent.progress(content));
    }

    /** Emits streamed visible content without persisting partial duplicates. */
    public synchronized void contentDelta(String content) {
        if (!sealed && content != null && !content.isEmpty()) {
            emit(AiExecutionEvent.contentDelta(content));
        }
    }

    public synchronized void changeConfirmationRequired(AiChangeConfirmationNotice notice) {
        if (sealed || notice == null) {
            return;
        }
        emit(AiExecutionEvent.detail("change_confirmation_required",
                "A change requires explicit approval.", Map.of(
                        "confirmationRequestId", notice.confirmationRequestId(),
                        "status", notice.status(),
                        "expiresAt", notice.expiresAt().toString(),
                        "toolName", notice.toolName(),
                        "argumentsSummary", notice.argumentsSummary())));
    }

    /** Emits one root-facing interaction for all approvals at the current execution barrier. */
    public synchronized void changeApprovalBatchRequired(AiChangeApprovalBatchNotice notice) {
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
        emit(AiExecutionEvent.detail("change_approval_batch_required",
                items.size() == 1
                        ? "A change requires explicit approval."
                        : items.size() + " changes require explicit approval.",
                Map.of("batchId", notice.batchId(),
                        "expiresAt", notice.expiresAt().toString(),
                        "parallel", notice.parallel(),
                        "items", items)));
    }

    /** Emits the committed user decision before any retained worker can resume. */
    public synchronized void changeApprovalDecisionAccepted(
            AiChangeApprovalCoordinator.DecisionAcknowledgement acknowledgement) {
        if (sealed || acknowledgement == null) {
            return;
        }
        long approved = acknowledgement.approved();
        long denied = acknowledgement.denied();
        emit(AiExecutionEvent.detail("change_approval_decision_accepted",
                "Approved " + approved + " change" + (approved == 1 ? "" : "s")
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
        metadata.put("generation", notice.generation());
        metadata.put("expiresAt", notice.expiresAt().toString());
        metadata.put("mode", "form");
        metadata.put("message", notice.message());
        metadata.put("requestedSchema", notice.requestedSchema());
        emit(AiExecutionEvent.detail("elicitation_required",
                "The assistant needs your input before it can continue.", metadata));
    }

    public synchronized ModelCallRecording beginModelCall(String phase) {
        return modelCalls.begin(phase);
    }

    public synchronized ExecutionEventIdentity recordModelResponse(ChatResponse response, String phase) {
        return modelCalls.recordLegacy(response, phase, false);
    }

    /** Records a response aggregated from streaming chunks with cache metadata loss in mind. */
    public synchronized ExecutionEventIdentity recordStreamingModelResponse(ChatResponse response, String phase) {
        return modelCalls.recordLegacy(response, phase, true);
    }

    public synchronized ExecutionEventIdentity recordModelResponse(
            ModelCallRecording call, ChatResponse response, boolean streaming) {
        return modelCalls.record(call, response, streaming);
    }

    public synchronized ExecutionEventIdentity failModelCall(ModelCallRecording call,
                                                              Throwable failure) {
        return modelCalls.fail(call, failure);
    }

    public record ModelCallRecording(long stepId, String phase, ExecutionEventIdentity started) {
        public static ModelCallRecording noop() {
            return new ModelCallRecording(-1L, "model", null);
        }
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
    public void changeApprovalsResolved(List<AiPendingChangeApproval> approvals) {
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
    public long executedChangeToolCallCount() {
        return executedChangeToolCalls.get();
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
        toolOutputLimiter.resetEstimatedInputFloor(inputTokens);
    }

    private Map<String, Object> arguments(String json) {
        return AiModelResponseProjection.arguments(json, objectMapper);
    }

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
                        AiToolRetryMessage.format(notice),
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
        persistAndEmit(new AiChatTrajectoryStep(
                requestId, "agent", "tool_call_update", "debug",
                "Calling " + pending.name() + ".", null, modelName,
                reasoningEffort,
                null, null, null, traceMetadata(extra), 0, null, null),
                AiExecutionEvent.tool("started", "Calling " + pending.name() + ".",
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
            executedChangeToolCalls.incrementAndGet();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_call_id", pending.id());
        result.put("content", auditText(output));
        result.put("extra", Map.of(
                "duration_ms", duration.toMillis(),
                "success", successful));
        pending.observations().results().put(pending.id(), result);
        updateModelObservation(pending);
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
        AiChatTrajectoryStep completedStep = new AiChatTrajectoryStep(
                requestId, "agent", "tool_call", "debug", detail, null, modelName,
                reasoningEffort,
                null, null, null, extra, 0, null, null);
        Map<String, Object> eventMetadata = new LinkedHashMap<>();
        eventMetadata.put("toolDetail", detail);
        eventMetadata.put("duration_ms", duration.toMillis());
        eventMetadata.put("success", successful);
        eventMetadata.put("result_truncated", resultTruncated);
        eventMetadata.put("read_only", readOnlyToolNames.contains(pending.name()));
        eventMetadata.put("mcp", mcpToolNames.contains(pending.name()));
        if (StringUtils.hasText(activeAgentRunId())) {
            eventMetadata.put("agent_run_id", activeAgentRunId());
        }
        if (mcpToolNames.contains(pending.name())) {
            eventMetadata.putAll(mcpObservationMetadata());
        }
        if (failure != null) eventMetadata.put("failure_type", failure.getClass().getName());
        persistAndEmit(completedStep, AiExecutionEvent.tool(status,
                switch (status) {
                    case "failed" -> pending.name() + " failed.";
                    case "blocked" -> pending.name() + " is awaiting approval.";
                    case "denied" -> pending.name() + " was denied before execution.";
                    case "cancelled" -> pending.name() + " was stopped before execution.";
                    default -> pending.name() + " completed.";
                },
                pending.id(), pending.name(), pending.sequence(), Map.copyOf(eventMetadata)));
    }

    private void updateModelObservation(AiPendingTool pending) {
        if (pending.observations().stepId() <= 0) return;
        Map<String, Object> observation = Map.of(
                "results", pending.observations().orderedResults());
        if (executionScope == null) {
            repository.updateObservation(conversationId, pending.observations().stepId(), observation);
            return;
        }
        observer.publish(ExecutionObservation.of("model.observation.updated", executionScope,
                        Map.of("tool_id", pending.id(), "tool_name", pending.name())),
                ignored -> repository.updateObservation(conversationId,
                        pending.observations().stepId(), observation));
    }

    /** Returns the stable confirmation identity embedded by the change guard. */
    private String pendingApprovalIdentity(String output, AiPendingTool pending) {
        if (!StringUtils.hasText(output)) {
            return null;
        }
        try {
            var root = objectMapper.readTree(output);
            var error = root != null && root.isObject() ? root.get("error") : null;
            if (error == null || !error.isTextual()
                    || !AiChangeToolGuard.CHANGE_CONFIRMATION_REQUIRED.equals(
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
        return hasTopLevelError(output, AiChangeToolGuard.REQUEST_STOPPING);
    }

    /** The guard returns a stable cached result when an exact change was denied. */
    private boolean deniedBeforeExecution(String output) {
        return hasTopLevelError(output, AiChangeToolGuard.CHANGE_CONFIRMATION_DENIED);
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
        return auditSanitizer.auditText(value);
    }

    private Object boundedRedactedValue(Object value) {
        return auditSanitizer.boundedValue(value);
    }

    private String toJson(Object value) {
        return auditSanitizer.json(value);
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
            AiPendingTool pending;
            // Bookkeeping runs under the terminal-sealing lock; the Tool call itself must not.
            // A server-to-client callback (an MCP elicitation, for one) arrives on another
            // thread while the call is in flight and has to reach this recorder to publish
            // itself, so holding the monitor across the call would deadlock both threads.
            synchronized (AiTrajectoryRecorder.this) {
                verifyActive();
                pending = pending(getToolDefinition().name(), input);
                toolStarted(pending);
            }
            Instant started = Instant.now();
            try (var ignored = observationContext.makeToolCurrent(requestId, pending.id())) {
                String output = delegate.call(input, context);
                synchronized (AiTrajectoryRecorder.this) {
                    AiBoundedToolOutput bounded = reserveToolOutput(output, toolOutputTokenLimit);
                    emitToolOutputTruncated(bounded, toolOutputTokenLimit, pending.name());
                    emitToolOutputUsage(bounded);
                    toolCompleted(pending, output, null, Duration.between(started, Instant.now()),
                            bounded.truncated());
                    return bounded.value();
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("AI tool {} failed for request {}", pending.name(), requestId, exception);
                synchronized (AiTrajectoryRecorder.this) {
                    toolCompleted(pending, null, exception, Duration.between(started, Instant.now()));
                }
                throw exception;
            }
        }
    }

    private synchronized AiBoundedToolOutput reserveToolOutput(String output,
                                                               long configuredLimit) {
        return toolOutputLimiter.reserve(output, configuredLimit);
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
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mcp", mcpToolNames.contains(toolName));
        if (mcpToolNames.contains(toolName)) metadata.putAll(mcpObservationMetadata());
        if (StringUtils.hasText(activeAgentRunId())) {
            metadata.put("agent_run_id", activeAgentRunId());
        }
        return Map.copyOf(metadata);
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
        AiContextUsageInfo usage = toolOutputLimiter.usageAfter(bounded);
        if (usage != null) emitContextUsage(usage);
    }

    private Map<String, Object> traceMetadata(Map<String, Object> metadata) {
        return eventWriter.traceMetadata(metadata);
    }

    private AiChatStoredStep persistAndEmit(AiChatTrajectoryStep step, AiExecutionEvent event) {
        return persist(step, event, true);
    }

    private AiChatStoredStep persistWithoutRealtime(AiChatTrajectoryStep step,
                                                     AiExecutionEvent event) {
        return persist(step, event, false);
    }

    private AiChatStoredStep persist(AiChatTrajectoryStep step, AiExecutionEvent event,
                                     boolean deliverRealtime) {
        return eventWriter.persist(step, event, deliverRealtime);
    }

    /**
     * @deprecated Use the execution-package canonical identity for new integrations. This facade
     * preserves the recorder's established source and binary contract.
     */
    @Deprecated(forRemoval = false)
    public record ExecutionEventIdentity(String eventId, long sequence, Instant occurredAt) {
        private static ExecutionEventIdentity from(ExecutionObservation event) {
            return org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity
                    .find(event)
                    .map(identity -> new ExecutionEventIdentity(identity.eventId(),
                            identity.sequence(), identity.occurredAt()))
                    .orElse(null);
        }

        private static ExecutionEventIdentity from(
                org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity identity) {
            return identity != null ? new ExecutionEventIdentity(
                    identity.eventId(), identity.sequence(), identity.occurredAt()) : null;
        }

        public org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity canonical() {
            return new org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity(
                    eventId, sequence, occurredAt);
        }

        private void putAttributes(Map<String, Object> target) {
            canonical().putAttributes(target);
        }

        private static void copyAttributes(Map<String, Object> source,
                                           Map<String, Object> target) {
            org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity
                    .copyAttributes(source, target);
        }

        private static void copyAttributes(ExecutionObservation source,
                                           Map<String, Object> target) {
            org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventIdentity
                    .copyAttributes(source, target);
        }
    }

    private synchronized void emit(AiExecutionEvent event) {
        eventWriter.emit(event);
    }

}

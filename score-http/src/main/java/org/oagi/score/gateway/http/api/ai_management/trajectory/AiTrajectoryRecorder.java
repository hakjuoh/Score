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
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiMetricsSnapshot;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Records ATIF-compatible execution history and publishes content-free lifecycle facts
 * through {@link ExecutionObserver} for independent consumers such as OpenTelemetry.
 */
public final class AiTrajectoryRecorder {

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
    private final AiTrajectoryToolCalls toolCalls;
    private final AtomicLong estimatedInputFloor;
    private final AtomicLong eventSequence;
    private final AtomicLong toolSequence;
    private final Map<String, Object> traceContext;
    private final AiChatConversationKind conversationKind;
    private final AtomicLong requestExecutedDomainToolCalls;
    private final Set<String> requestPendingApprovalIds;
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
        this.toolCalls = new AiTrajectoryToolCalls(
                this, repository, objectMapper, conversationId, requestId, modelName,
                reasoningEffort, executionScope, this.observer, this.observationContext,
                eventWriter, auditSanitizer, toolOutputLimiter, toolSequence,
                requestExecutedDomainToolCalls, requestPendingApprovalIds,
                () -> sealed, this::verifyActive, this::appendGuide,
                this::activeAgentRunId, conversationKind != AiChatConversationKind.ROOT
                        || this.traceContext.containsKey("agent_id")
                        || Boolean.TRUE.equals(this.traceContext.get("concurrent_branch")));
        this.modelCalls = new AiTrajectoryModelCalls(
                repository, objectMapper, conversationId, requestId, modelName, reasoningEffort,
                contextBudget, this.estimatedInputFloor, promptTokenNormalizer, executionScope,
                this.observer, eventWriter, auditSanitizer::boundedValue, toolCalls::enqueue,
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
        toolCalls.mcpToolNames(toolNames);
    }

    public void mcpServerName(String serverName) {
        toolCalls.mcpServerName(serverName);
    }

    public void mcpTelemetry(String serverName, String protocolVersion, String serverAddress,
                             long serverPort, String networkProtocolName,
                             String networkTransport) {
        toolCalls.mcpTelemetry(serverName, protocolVersion, serverAddress, serverPort,
                networkProtocolName, networkTransport);
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
            toolCalls.clearPending();
        }
    }

    /** Silently rejects callbacks that arrive after an enclosing execution has terminated. */
    public synchronized void sealAgainstLateCallbacks() {
        sealed = true;
        toolCalls.clearPending();
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

    public synchronized void recordToolResponses(List<Message> messages) {
        toolCalls.recordResponses(messages);
    }

    /**
     * Number of tool calls this recorder has observed to completion. Callers use
     * changes in this count as segment boundaries in the visible answer stream:
     * narration emitted before a tool call is interim commentary, not the answer.
     */
    public long completedToolCallCount() {
        return toolCalls.completedCount();
    }

    /** Number of completed connectCenter calls, excluding the tool-discovery helper. */
    public long completedDomainToolCallCount() {
        return toolCalls.completedDomainCount();
    }

    /** Number of successful connectCenter calls local to this recorder, excluding tool discovery. */
    public long successfulDomainToolCallCount() {
        return toolCalls.successfulDomainCount();
    }

    /**
     * Request-wide count of connectCenter domain tool calls that executed successfully,
     * shared across every forked worker and lead recorder. Guard-intercepted and failed
     * calls are excluded: this is the evaluator's grounding evidence, not a boundary marker.
     */
    public long executedDomainToolCallCount() {
        return toolCalls.executedDomainCount();
    }

    /** Request-wide count of data-changing calls intercepted and awaiting user approval. */
    public long pendingApprovalCount() {
        return toolCalls.pendingApprovalCount();
    }

    /** Removes the exact intercepted approvals from request-wide evaluator evidence. */
    public void changeApprovalsResolved(List<AiPendingChangeApproval> approvals) {
        toolCalls.approvalsResolved(approvals);
    }

    /**
     * Tool calls this recorder observed that actually ran a non-read-only tool.
     * A model attempt whose count moved must never be replayed by the provider
     * retry loop: re-running it could repeat the data change.
     */
    public long executedChangeToolCallCount() {
        return toolCalls.executedChangeCount();
    }

    /**
     * Declares the MCP-session tools the server annotated read-only so every recorded
     * tool step carries the guard classification external verifiers evaluate against.
     */
    public void readOnlyToolNames(Set<String> names) {
        toolCalls.readOnlyToolNames(names);
    }

    public ToolCallbackProvider recordingTools(ToolCallbackProvider delegate) {
        return recordingTools(delegate, Long.MAX_VALUE);
    }

    public ToolCallbackProvider recordingTools(ToolCallbackProvider delegate, long toolOutputTokenLimit) {
        return toolCalls.recording(delegate, toolOutputTokenLimit);
    }

    public String limitToolOutput(String output, long toolOutputTokenLimit) {
        return limitToolOutput(output, toolOutputTokenLimit, "approved_tool");
    }

    public String limitToolOutput(String output, long toolOutputTokenLimit, String toolName) {
        return toolCalls.limitOutput(output, toolOutputTokenLimit, toolName);
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

    private void emitContextUsage(AiContextUsageInfo usage) {
        emit(AiExecutionEvent.detail("context_usage", "Context usage updated.",
                Map.of("contextUsage", usage)));
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

package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalBatchNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private final String conversationId;
    private final String requestId;
    private final String modelName;
    private final String reasoningEffort;
    private final Consumer<AiExecutionEvent> realtimeEvents;
    private final ExecutionScope executionScope;
    private final ExecutionObserver observer;
    private final ExecutionObservationContext observationContext;
    private final AiTrajectoryEventWriter eventWriter;
    private final AiTrajectoryAuditSanitizer auditSanitizer;
    private final AiTrajectoryModelCalls modelCalls;
    private final AiToolOutputLimiter toolOutputLimiter;
    private final AiTrajectoryInteractions interactions;
    private final AiTrajectoryToolCalls toolCalls;
    private final AiTrajectoryLifecycleGate lifecycleGate;
    private final AiTrajectoryForks forks;
    private final AtomicLong estimatedInputFloor;
    private final Map<String, Object> traceContext;
    private final AiChatConversationKind conversationKind;
    private final AiTrajectoryRuntimeState runtimeState = new AiTrajectoryRuntimeState();

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
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.modelName = modelName;
        this.reasoningEffort = reasoningEffort;
        this.realtimeEvents = events != null ? events : ignored -> {};
        this.executionScope = executionScope;
        this.observer = observer != null ? observer : ExecutionObserver.noop();
        this.observationContext = observationContext != null
                ? observationContext : ExecutionObservationContext.noop();
        this.estimatedInputFloor = new AtomicLong(Math.max(0L, estimatedInputFloor));
        this.traceContext = traceContext != null ? Map.copyOf(traceContext) : Map.of();
        this.conversationKind = conversationKind;
        this.eventWriter = new AiTrajectoryEventWriter(repository, conversationId,
                requestId, this.realtimeEvents, executionScope, this.observer,
                this.traceContext, this::activeAgentRunId, runtimeState::disclosureSealed);
        this.auditSanitizer = new AiTrajectoryAuditSanitizer(objectMapper);
        this.toolOutputLimiter = new AiToolOutputLimiter(
                contextBudget, this.estimatedInputFloor, subagentScope);
        this.interactions = new AiTrajectoryInteractions(
                requestId, modelName, reasoningEffort, contextBudget,
                this.estimatedInputFloor, eventSequence, eventWriter,
                runtimeState::disclosureSealed);
        this.toolCalls = new AiTrajectoryToolCalls(
                this, repository, objectMapper, conversationId, requestId, modelName,
                reasoningEffort, executionScope, this.observer, this.observationContext,
                eventWriter, auditSanitizer, toolOutputLimiter, toolSequence,
                requestExecutedDomainToolCalls, requestPendingApprovalIds,
                runtimeState::disclosureSealed, this::verifyActive, interactions::guide,
                this::activeAgentRunId, conversationKind != AiChatConversationKind.ROOT
                        || this.traceContext.containsKey("agent_id")
                        || Boolean.TRUE.equals(this.traceContext.get("concurrent_branch")));
        this.lifecycleGate = new AiTrajectoryLifecycleGate(
                runtimeState, interactions, toolCalls);
        this.modelCalls = new AiTrajectoryModelCalls(
                repository, objectMapper, conversationId, requestId, modelName, reasoningEffort,
                contextBudget, this.estimatedInputFloor, promptTokenNormalizer, executionScope,
                this.observer, eventWriter, auditSanitizer::boundedValue, toolCalls::enqueue,
                interactions::contextUsage, runtimeState::disclosureSealed,
                runtimeState::usageAccountingSealed,
                subagentScope, this.traceContext);
        this.forks = new AiTrajectoryForks(
                repository, conversationId, requestId, modelName, reasoningEffort,
                executionScope, conversationKind, eventWriter,
                (childId, childTrace, childScope, childKind) -> new AiTrajectoryRecorder(
                        repository, objectMapper, requester, childId, requestId,
                        modelName, reasoningEffort, this.realtimeEvents, contextBudget,
                        this.estimatedInputFloor.get(), modelCalls.promptTokenNormalizer(),
                        eventSequence, toolSequence, requestExecutedDomainToolCalls,
                        requestPendingApprovalIds, childTrace, childScope, this.observer,
                        this.observationContext, true, childKind));
    }

    /**
     * Forks correlation state while sharing request-wide event/tool order and evidence counters.
     * Child model usage remains subagent-scoped until the parent records settled fan-out usage.
     */
    public AiTrajectoryRecorder fork(Map<String, Object> namespace) {
        return forks.fork(namespace);
    }

    /** Creates a durable SUBAGENT conversation with a WORKER execution scope. */
    public AiTrajectoryRecorder forkSubagent(String agentId, String assignment,
                                              Map<String, Object> namespace) {
        return forks.forkSubagent(agentId, assignment, namespace);
    }

    /** Creates a durable PARALLEL conversation with a WORKER execution scope. */
    public AiTrajectoryRecorder forkParallelExecution(String workerId, String assignment,
                                                       Map<String, Object> namespace) {
        return forks.forkParallel(workerId, assignment, namespace);
    }

    public String conversationId() { return conversationId; }
    public AiChatConversationKind conversationKind() { return conversationKind; }

    public String requestId() { return requestId; }
    public String modelName() { return modelName; }
    public String requestModelName() { return modelCalls.requestModelName(); }
    public String modelProvider() { return modelCalls.provider(); }
    public long executionGeneration() {
        return executionScope != null ? executionScope.generation() : 0L;
    }
    public String activeAgentRunId() {
        return runtimeState.activeAgentRunId();
    }

    public AgentRunActivation activateAgentRun(String runId) {
        Runnable close = runtimeState.activateAgentRun(runId);
        return close::run;
    }

    @FunctionalInterface
    public interface AgentRunActivation extends AutoCloseable {
        @Override
        void close();

        static AgentRunActivation noop() { return () -> { }; }
    }

    public synchronized void recordUserMessage(String content, Map<String, Object> metadata) {
        interactions.userMessage(content, metadata);
    }

    public synchronized void recordAssistantMessage(String content, Map<String, Object> metadata) {
        interactions.assistantMessage(content, metadata);
    }

    public Map<String, Object> observationContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("fanout_id", "node_id", "parent_node_id",
                "workflow", "workflow_iteration")) {
            if (traceContext.get(key) != null) context.put(key, traceContext.get(key));
        }
        return Map.copyOf(context);
    }

    public void mcpToolNames(java.util.Collection<String> names) { toolCalls.mcpToolNames(names); }
    public void mcpServerName(String name) { toolCalls.mcpServerName(name); }

    public void mcpTelemetry(String serverName, String protocolVersion, String serverAddress,
                             long serverPort, String networkProtocolName,
                             String networkTransport) {
        toolCalls.mcpTelemetry(serverName, protocolVersion, serverAddress, serverPort,
                networkProtocolName, networkTransport);
    }

    public void useModelProvider(String providerType) { modelCalls.useProvider(providerType); }
    public void useModelProvider(String providerType, String requestModelName) {
        modelCalls.useProvider(providerType, requestModelName);
    }

    /** Returns usage local to this recorder's fan-out namespace. */
    public AiUsageSnapshot usageSnapshot() {
        return modelCalls.usageSnapshot();
    }

    /**
     * Writes one authoritative fan-out aggregate using dedicated metrics so child model rows
     * and their settled total cannot be double-counted as ordinary prompt/completion usage.
     */
    public synchronized void recordFanOutUsage(String fanoutId, List<AiUsageSnapshot> agents) {
        recordFanOutUsage(fanoutId, "multi_agent", agents);
    }

    public synchronized void recordFanOutUsage(String fanoutId, String executionKind,
                                                List<AiUsageSnapshot> agents) {
        lifecycleGate.fanOutUsage(fanoutId, executionKind, agents);
    }

    /** Accounting-only terminal write; it cannot disclose model or tool content. */
    public synchronized void recordSettledFanOutUsage(
            String fanoutId, String executionKind, List<AiUsageSnapshot> agents) {
        lifecycleGate.settledFanOutUsage(fanoutId, executionKind, agents);
    }

    public synchronized void lifecycle(String subtype, String content, Map<String, Object> metadata) {
        lifecycleGate.lifecycle(subtype, content, metadata);
    }

    /** Rejects provider callbacks that race with terminal request cleanup. */
    public synchronized void verifyActive() {
        lifecycleGate.verifyActive();
    }

    /** Runs a side-effecting callback under the same lock used by terminal sealing. */
    public synchronized <T> T callWhileActive(Supplier<T> action) {
        return lifecycleGate.callWhileActive(action);
    }

    /** Atomically records the terminal transition and closes the disclosure fence. */
    public synchronized void terminalLifecycle(String subtype, String content,
                                               Map<String, Object> metadata) {
        lifecycleGate.terminalLifecycle(subtype, content, metadata);
    }

    /** Silently rejects callbacks arriving after an enclosing execution terminates. */
    public synchronized void sealAgainstLateCallbacks() {
        lifecycleGate.sealDisclosure();
    }

    /** Stops accounting-only updates after the bounded provider settlement window. */
    public synchronized void sealUsageAccounting() {
        lifecycleGate.sealUsageAccounting();
    }
    public synchronized boolean guide(String content, Map<String, Object> metadata) {
        return interactions.guide(content, metadata, true);
    }

    public synchronized void workflowResult(AgentOutput output, Map<String, Object> metadata) {
        interactions.workflowResult(output, metadata);
    }

    public synchronized void resetGuideDeduplication() { interactions.resetGuideDeduplication(); }
    public synchronized void providerRetry(int attempt, int maxAttempts, long delayMillis,
                                           String reason, String failureClass, int statusCode) {
        interactions.providerRetry(
                attempt, maxAttempts, delayMillis, reason, failureClass, statusCode);
    }

    public synchronized void progress(String content) { interactions.progress(content); }
    public synchronized void contentDelta(String content) { interactions.contentDelta(content); }

    public synchronized void changeConfirmationRequired(AiChangeConfirmationNotice notice) {
        interactions.confirmationRequired(notice);
    }

    public synchronized void changeApprovalBatchRequired(AiChangeApprovalBatchNotice notice) {
        interactions.approvalBatchRequired(notice);
    }

    public synchronized void changeApprovalDecisionAccepted(
            AiChangeApprovalCoordinator.DecisionAcknowledgement acknowledgement) {
        interactions.approvalDecisionAccepted(acknowledgement);
    }

    public synchronized void elicitationRequired(AiElicitationNotice notice) {
        interactions.elicitationRequired(notice);
    }

    public synchronized ModelCallRecording beginModelCall(String phase) {
        return modelCalls.begin(phase);
    }

    public synchronized ExecutionEventIdentity recordModelResponse(ChatResponse response, String phase) {
        return modelCalls.recordLegacy(response, phase, false);
    }

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

    /** Local answer-segment boundary count, including discovery calls. */
    public long completedToolCallCount() { return toolCalls.completedCount(); }
    public long completedDomainToolCallCount() { return toolCalls.completedDomainCount(); }
    public long successfulDomainToolCallCount() { return toolCalls.successfulDomainCount(); }
    /** Request-wide successful domain execution evidence shared by every fork. */
    public long executedDomainToolCallCount() { return toolCalls.executedDomainCount(); }
    /** Request-wide intercepted changes awaiting user approval. */
    public long pendingApprovalCount() { return toolCalls.pendingApprovalCount(); }

    public void changeApprovalsResolved(List<AiPendingChangeApproval> approvals) {
        toolCalls.approvalsResolved(approvals);
    }

    /** Replay fence: non-read-only calls that may have executed must not be retried. */
    public long executedChangeToolCallCount() { return toolCalls.executedChangeCount(); }
    /** Installs trusted read-only classifications used by persistence and replay fencing. */
    public void readOnlyToolNames(Set<String> names) { toolCalls.readOnlyToolNames(names); }

    public ToolCallbackProvider recordingTools(ToolCallbackProvider delegate) {
        return recordingTools(delegate, Long.MAX_VALUE);
    }

    public ToolCallbackProvider recordingTools(ToolCallbackProvider delegate, long toolOutputTokenLimit) {
        return toolCalls.recording(delegate, toolOutputTokenLimit);
    }

    public String limitToolOutput(String output, long limit) {
        return limitToolOutput(output, limit, "approved_tool");
    }

    public String limitToolOutput(String output, long toolOutputTokenLimit, String toolName) {
        return toolCalls.limitOutput(output, toolOutputTokenLimit, toolName);
    }

    public synchronized void contextCompacted(String reason, long beforeTokens,
                                              AiContextUsageInfo usage, boolean automatic) {
        interactions.contextCompacted(reason, beforeTokens, usage, automatic);
    }

    public synchronized void contextUsage(AiContextUsageInfo usage) {
        interactions.contextUsage(usage);
    }
    public void resetEstimatedInputFloor(long tokens) {
        toolOutputLimiter.resetEstimatedInputFloor(tokens);
    }

    Map<String, Object> traceMetadata(Map<String, Object> metadata) {
        return eventWriter.traceMetadata(metadata);
    }

    AiChatStoredStep persistWithoutRealtime(AiChatTrajectoryStep step,
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

}

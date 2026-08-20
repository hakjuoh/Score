package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservationContext;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.model.AiBoundedToolOutput;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStepId;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiObservationAccumulator;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingTool;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeToolGuard;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolFailureMessage;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolRetryMessage;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolRetryTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns tool-call correlation, lifecycle recording, evidence counters, and MCP metadata. */
final class AiTrajectoryToolCalls {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTrajectoryToolCalls.class);

    @FunctionalInterface
    interface GuideSink {
        void append(String content, Map<String, Object> metadata, boolean deduplicate);
    }

    /** Recorder terminal-sealing monitor; never held while the delegate tool is running. */
    private final Object monitor;
    private final AiChatConversationRepository repository;
    private final ObjectMapper objectMapper;
    private final String conversationId;
    private final String requestId;
    private final String modelName;
    private final String reasoningEffort;
    private final ExecutionScope executionScope;
    private final ExecutionObserver observer;
    private final ExecutionObservationContext observationContext;
    private final AiTrajectoryEventWriter eventWriter;
    private final AiTrajectoryAuditSanitizer auditSanitizer;
    private final AiToolOutputLimiter outputLimiter;
    private final AtomicLong toolSequence;
    private final AtomicLong requestExecutedDomainToolCalls;
    private final Set<String> requestPendingApprovalIds;
    private final BooleanSupplier sealed;
    private final Runnable verifyActive;
    private final GuideSink guideSink;
    private final Supplier<String> activeAgentRunId;
    private final boolean delegatedWorkerScope;
    private final Map<String, ConcurrentLinkedQueue<AiPendingTool>> pendingTools =
            new ConcurrentHashMap<>();
    private final Set<String> completedToolCallIds = ConcurrentHashMap.newKeySet();
    private final Set<String> narratedToolCallIds = ConcurrentHashMap.newKeySet();
    private final AiToolRetryTracker retryTracker = new AiToolRetryTracker();
    private final AtomicLong completedToolCalls = new AtomicLong();
    private final AtomicLong completedDomainToolCalls = new AtomicLong();
    private final AtomicLong successfulDomainToolCalls = new AtomicLong();
    private final AtomicLong executedChangeToolCalls = new AtomicLong();
    private final Set<String> mcpToolNames = ConcurrentHashMap.newKeySet();
    private volatile Set<String> readOnlyToolNames = Set.of();
    private volatile String mcpServerName = "unknown";
    private volatile String mcpProtocolVersion;
    private volatile String mcpServerAddress;
    private volatile long mcpServerPort = -1;
    private volatile String mcpNetworkProtocolName;
    private volatile String mcpNetworkTransport;

    AiTrajectoryToolCalls(Object monitor, AiChatConversationRepository repository,
                          ObjectMapper objectMapper, String conversationId, String requestId,
                          String modelName, String reasoningEffort, ExecutionScope executionScope,
                          ExecutionObserver observer, ExecutionObservationContext observationContext,
                          AiTrajectoryEventWriter eventWriter,
                          AiTrajectoryAuditSanitizer auditSanitizer,
                          AiToolOutputLimiter outputLimiter, AtomicLong toolSequence,
                          AtomicLong requestExecutedDomainToolCalls,
                          Set<String> requestPendingApprovalIds, BooleanSupplier sealed,
                          Runnable verifyActive, GuideSink guideSink,
                          Supplier<String> activeAgentRunId, boolean delegatedWorkerScope) {
        this.monitor = monitor;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.modelName = modelName;
        this.reasoningEffort = reasoningEffort;
        this.executionScope = executionScope;
        this.observer = observer;
        this.observationContext = observationContext;
        this.eventWriter = eventWriter;
        this.auditSanitizer = auditSanitizer;
        this.outputLimiter = outputLimiter;
        this.toolSequence = toolSequence;
        this.requestExecutedDomainToolCalls = requestExecutedDomainToolCalls;
        this.requestPendingApprovalIds = requestPendingApprovalIds;
        this.sealed = sealed;
        this.verifyActive = verifyActive;
        this.guideSink = guideSink;
        this.activeAgentRunId = activeAgentRunId;
        this.delegatedWorkerScope = delegatedWorkerScope;
    }

    void enqueue(String name, String callId, Object arguments,
                 AiObservationAccumulator observations) {
        pendingTools.computeIfAbsent(name, ignored -> new ConcurrentLinkedQueue<>())
                .add(new AiPendingTool(callId, name, arguments, observations,
                        toolSequence.getAndIncrement()));
    }

    void clearPending() {
        pendingTools.clear();
    }

    void mcpToolNames(Collection<String> names) {
        mcpToolNames.clear();
        if (names != null) names.stream().filter(StringUtils::hasText).map(String::strip)
                .forEach(mcpToolNames::add);
    }

    void mcpServerName(String name) {
        mcpServerName = StringUtils.hasText(name) ? name.strip() : "unknown";
    }

    void mcpTelemetry(String name, String version, String address, long port,
                      String protocol, String transport) {
        mcpServerName(name);
        mcpProtocolVersion = normalized(version);
        mcpServerAddress = normalized(address);
        mcpServerPort = port > 0 && port <= 65_535 ? port : -1;
        mcpNetworkProtocolName = normalized(protocol);
        mcpNetworkTransport = normalized(transport);
    }

    void recordResponses(List<Message> messages) {
        if (sealed.getAsBoolean() || messages == null) return;
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage responses)) continue;
            for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
                if (response.id().startsWith("approved-")
                        || completedToolCallIds.contains(response.id())) continue;
                AiPendingTool pending = pendingTools
                        .computeIfAbsent(response.name(), ignored -> new ConcurrentLinkedQueue<>())
                        .poll();
                if (pending == null) {
                    pending = new AiPendingTool(response.id(), response.name(), Map.of(),
                            new AiObservationAccumulator(AiChatStepId.NONE, List.of()),
                            toolSequence.getAndIncrement());
                }
                started(pending);
                completed(pending, response.responseData(), null, Duration.ZERO, false);
            }
        }
    }

    long completedCount() { return completedToolCalls.get(); }
    long completedDomainCount() { return completedDomainToolCalls.get(); }
    long successfulDomainCount() { return successfulDomainToolCalls.get(); }
    long executedDomainCount() { return requestExecutedDomainToolCalls.get(); }
    long pendingApprovalCount() { return requestPendingApprovalIds.size(); }
    long executedChangeCount() { return executedChangeToolCalls.get(); }

    void approvalsResolved(List<AiPendingChangeApproval> approvals) {
        if (approvals == null) return;
        approvals.forEach(approval -> {
            requestPendingApprovalIds.remove(approval.notice().confirmationRequestId());
            requestPendingApprovalIds.remove(toolApprovalIdentity(
                    approval.toolName(), arguments(approval.arguments())));
        });
    }

    void readOnlyToolNames(Set<String> names) {
        readOnlyToolNames = names != null ? Set.copyOf(names) : Set.of();
    }

    ToolCallbackProvider recording(ToolCallbackProvider delegate, long outputTokenLimit) {
        ToolCallback[] callbacks = delegate != null
                ? delegate.getToolCallbacks() : new ToolCallback[0];
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int index = 0; index < callbacks.length; index++) {
            wrapped[index] = new RecordingCallback(callbacks[index], outputTokenLimit);
        }
        return () -> wrapped;
    }

    String limitOutput(String output, long configuredLimit, String toolName) {
        synchronized (monitor) {
            long reservationLimit = configuredLimit > 0 ? configuredLimit : Long.MAX_VALUE;
            AiBoundedToolOutput bounded = outputLimiter.reserve(output, reservationLimit);
            emitTruncated(bounded, configuredLimit, toolName);
            emitUsage(bounded);
            return bounded.value();
        }
    }

    private AiPendingTool pending(String toolName, String input) {
        ConcurrentLinkedQueue<AiPendingTool> queue = pendingTools.get(toolName);
        Object parsed = arguments(input);
        AiPendingTool pending = queue != null ? queue.stream()
                .filter(candidate -> Objects.equals(candidate.arguments(), parsed))
                .findFirst().orElse(null) : null;
        if (pending != null) queue.remove(pending);
        else if (queue != null) pending = queue.poll();
        return pending != null ? pending : new AiPendingTool(
                UUID.randomUUID().toString(), toolName, parsed,
                new AiObservationAccumulator(AiChatStepId.NONE, List.of()),
                toolSequence.getAndIncrement());
    }

    private void started(AiPendingTool pending) {
        if (sealed.getAsBoolean()) return;
        boolean narrated = narratedToolCallIds.remove(pending.id()) || delegatedWorkerScope;
        retryTracker.retry(pending, narrated).ifPresent(notice -> guideSink.append(
                AiToolRetryMessage.format(notice),
                Map.of("phase", "assistant", "tool_retry", true,
                        "tool_name", pending.name()), false));
        Map<String, Object> extra = toolExtra(pending, "started");
        eventWriter.persist(new AiChatTrajectoryStep(
                        requestId, "agent", "tool_call_update", "visible",
                        "Calling " + pending.name() + ".", null, modelName, reasoningEffort,
                        null, null, null, eventWriter.traceMetadata(extra), 0, null, null),
                AiExecutionEvent.tool("started", "Calling " + pending.name() + ".",
                        pending.id(), pending.name(), pending.sequence(),
                        observationMetadata(pending.name())), true);
    }

    private void completed(AiPendingTool pending, String output, Throwable failure,
                           Duration duration, boolean resultTruncated) {
        if (sealed.getAsBoolean() || !completedToolCallIds.add(pending.id())) return;
        String approval = failure == null ? approvalIdentity(output, pending) : null;
        String status = failure != null ? "failed" : approval != null ? "blocked"
                : denied(output) ? "denied" : stopped(output) ? "cancelled" : "completed";
        boolean successful = "completed".equals(status);
        if (failure != null) retryTracker.failed(pending);
        countCompletion(pending, status, approval);
        pending.observations().results().put(pending.id(), Map.of(
                "source_call_id", pending.id(), "content", auditSanitizer.auditText(output),
                "extra", Map.of("duration_ms", duration.toMillis(), "success", successful)));
        updateObservation(pending);
        String detail = detail(pending, output, failure);
        Map<String, Object> extra = toolExtra(pending, status);
        extra.put("duration_ms", duration.toMillis());
        extra.put("success", successful);
        extra.put("result_truncated", resultTruncated);
        if (failure != null) extra.put("failure_type", failure.getClass().getName());
        Map<String, Object> event = new LinkedHashMap<>(observationMetadata(pending.name()));
        event.put("toolDetail", detail);
        event.put("duration_ms", duration.toMillis());
        event.put("success", successful);
        event.put("result_truncated", resultTruncated);
        event.put("read_only", readOnlyToolNames.contains(pending.name()));
        if (failure != null) event.put("failure_type", failure.getClass().getName());
        eventWriter.persist(new AiChatTrajectoryStep(
                        requestId, "agent", "tool_call", "visible", detail, null,
                        modelName, reasoningEffort, null, null, null,
                        eventWriter.traceMetadata(extra), 0, null, null),
                AiExecutionEvent.tool(status, statusMessage(status, pending.name()),
                        pending.id(), pending.name(), pending.sequence(), Map.copyOf(event)), true);
    }

    private void countCompletion(AiPendingTool pending, String status, String approval) {
        completedToolCalls.incrementAndGet();
        if (!"toolSearchTool".equals(pending.name())) {
            completedDomainToolCalls.incrementAndGet();
            if ("completed".equals(status)) {
                successfulDomainToolCalls.incrementAndGet();
                requestExecutedDomainToolCalls.incrementAndGet();
            }
        }
        if ("blocked".equals(status)) requestPendingApprovalIds.add(approval);
        if (("completed".equals(status) || "failed".equals(status))
                && !"toolSearchTool".equals(pending.name())
                && !readOnlyToolNames.contains(pending.name())) {
            executedChangeToolCalls.incrementAndGet();
        }
    }

    private Map<String, Object> toolExtra(AiPendingTool pending, String status) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tool_call_id", pending.id());
        extra.put("tool_name", pending.name());
        extra.put("tool_status", status);
        extra.put("read_only", readOnlyToolNames.contains(pending.name()));
        extra.put("tool_call_sequence", pending.sequence());
        extra.put("arguments", auditSanitizer.boundedValue(pending.arguments()));
        return extra;
    }

    private void updateObservation(AiPendingTool pending) {
        if (!pending.observations().stepId().isPersisted()) return;
        Map<String, Object> observation = Map.of(
                "results", pending.observations().orderedResults());
        if (executionScope == null) {
            repository.updateObservation(conversationId, pending.observations().stepId(), observation);
        } else {
            observer.publish(ExecutionObservation.of("model.observation.updated", executionScope,
                            Map.of("tool_id", pending.id(), "tool_name", pending.name())),
                    ignored -> repository.updateObservation(conversationId,
                            pending.observations().stepId(), observation));
        }
    }

    private String approvalIdentity(String output, AiPendingTool pending) {
        if (!StringUtils.hasText(output)) return null;
        try {
            var root = objectMapper.readTree(output);
            var error = root != null && root.isObject() ? root.get("error") : null;
            if (error == null || !error.isTextual()
                    || !AiChangeToolGuard.CHANGE_CONFIRMATION_REQUIRED.equals(error.textValue())) {
                return null;
            }
            var id = root.get("confirmationRequestId");
            return id != null && id.isTextual() && StringUtils.hasText(id.textValue())
                    ? id.textValue() : toolApprovalIdentity(pending.name(), pending.arguments());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean denied(String output) {
        return hasError(output, AiChangeToolGuard.CHANGE_CONFIRMATION_DENIED);
    }

    private boolean stopped(String output) {
        return hasError(output, AiChangeToolGuard.REQUEST_STOPPING);
    }

    private boolean hasError(String output, String expected) {
        if (!StringUtils.hasText(output)) return false;
        try {
            var root = objectMapper.readTree(output);
            var error = root != null && root.isObject() ? root.get("error") : null;
            return error != null && error.isTextual() && expected.equals(error.textValue());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String detail(AiPendingTool pending, String output, Throwable failure) {
        StringBuilder detail = new StringBuilder(pending.name()).append("\nArguments: ")
                .append(auditSanitizer.json(auditSanitizer.boundedValue(pending.arguments())));
        return failure == null
                ? detail.append("\nResult: ").append(auditSanitizer.auditText(output)).toString()
                : detail.append("\nError: ").append(AiToolFailureMessage.userMessage(failure)).toString();
    }

    private Map<String, Object> observationMetadata(String toolName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mcp", mcpToolNames.contains(toolName));
        if (mcpToolNames.contains(toolName)) metadata.putAll(mcpMetadata());
        String runId = activeAgentRunId.get();
        if (StringUtils.hasText(runId)) metadata.put("agent_run_id", runId);
        return Map.copyOf(metadata);
    }

    private Map<String, Object> mcpMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mcp", true);
        metadata.put("mcp_server_name", mcpServerName);
        if (mcpProtocolVersion != null) metadata.put("mcp_protocol_version", mcpProtocolVersion);
        if (mcpServerAddress != null) metadata.put("server_address", mcpServerAddress);
        if (mcpServerPort > 0) metadata.put("server_port", mcpServerPort);
        if (mcpNetworkProtocolName != null) metadata.put("network_protocol_name", mcpNetworkProtocolName);
        if (mcpNetworkTransport != null) metadata.put("network_transport", mcpNetworkTransport);
        return metadata;
    }

    private void emitTruncated(AiBoundedToolOutput bounded, long configuredLimit,
                               String toolName) {
        if (!bounded.truncated()) return;
        String safeName = StringUtils.hasText(toolName) ? toolName : "tool";
        eventWriter.emit(AiExecutionEvent.detail("tool_output_truncated",
                safeName + " returned more data than the active context budget allows.", Map.of(
                        "toolName", safeName, "mcp", mcpToolNames.contains(safeName),
                        "originalUtf8Bytes", bounded.originalBytes(),
                        "returnedUtf8Bytes", bounded.returnedBytes(),
                        "toolOutputTokenLimit", configuredLimit)));
    }

    private void emitUsage(AiBoundedToolOutput bounded) {
        var usage = outputLimiter.usageAfter(bounded);
        if (usage != null) eventWriter.emit(AiExecutionEvent.detail(
                "context_usage", "Context usage updated.", Map.of("contextUsage", usage)));
    }

    private Object arguments(String json) {
        return AiModelResponseProjection.arguments(json, objectMapper);
    }

    private String toolApprovalIdentity(String name, Object arguments) {
        return "tool:" + Objects.toString(name, "") + "\u0000" + auditSanitizer.json(arguments);
    }

    private String statusMessage(String status, String name) {
        return switch (status) {
            case "failed" -> name + " failed.";
            case "blocked" -> name + " is awaiting approval.";
            case "denied" -> name + " was denied before execution.";
            case "cancelled" -> name + " was stopped before execution.";
            default -> name + " completed.";
        };
    }

    private String normalized(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private final class RecordingCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final long outputTokenLimit;

        private RecordingCallback(ToolCallback delegate, long outputTokenLimit) {
            this.delegate = delegate;
            this.outputTokenLimit = outputTokenLimit > 0 ? outputTokenLimit : Long.MAX_VALUE;
        }

        @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
        @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
        @Override public String call(String input) { return call(input, new ToolContext(Map.of())); }

        @Override
        public String call(String input, ToolContext context) {
            AiPendingTool pending;
            synchronized (monitor) {
                verifyActive.run();
                pending = pending(getToolDefinition().name(), input);
                started(pending);
            }
            Instant started = Instant.now();
            try (var ignored = observationContext.makeToolCurrent(requestId, pending.id())) {
                String output = delegate.call(input, context);
                synchronized (monitor) {
                    AiBoundedToolOutput bounded = outputLimiter.reserve(output, outputTokenLimit);
                    emitTruncated(bounded, outputTokenLimit, pending.name());
                    emitUsage(bounded);
                    completed(pending, output, null, Duration.between(started, Instant.now()),
                            bounded.truncated());
                    return bounded.value();
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("AI tool {} failed for request {}", pending.name(), requestId, exception);
                synchronized (monitor) {
                    completed(pending, null, exception, Duration.between(started, Instant.now()), false);
                }
                throw exception;
            }
        }
    }
}

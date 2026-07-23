package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Converts existing trajectory lifecycle events into content-free spans and metrics. */
final class AiLifecycleEventObserver {

    private final Tracer tracer;
    private final AiObservationInstruments instruments;
    private final Function<String, Context> parents;
    private final AiLifecycleOperationRegistry<OperationKey, TimedSpan> workflows =
            new AiLifecycleOperationRegistry<>();
    private final AiLifecycleOperationRegistry<OperationKey, TimedSpan> tools =
            new AiLifecycleOperationRegistry<>();
    private final AiLifecycleOperationRegistry<OperationKey, Long> approvals =
            new AiLifecycleOperationRegistry<>();
    private final AiLifecycleOperationRegistry<OperationKey, Long> elicitations =
            new AiLifecycleOperationRegistry<>();

    AiLifecycleEventObserver(Tracer tracer, AiObservationInstruments instruments,
                             Function<String, Context> parents) {
        this.tracer = tracer;
        this.instruments = instruments;
        this.parents = parents;
    }

    void observe(String requestId, AiExecutionLifecycle event) {
        if (event == null || requestId == null) return;
        String subtype = ScoreAiObservability.value(event.subtype()).toLowerCase();
        if ("tool_call".equals(event.eventType())) {
            observeTool(requestId, subtype, event);
            return;
        }
        switch (subtype) {
            case "provider_retry" -> observeProviderRetry(requestId, event.metadata());
            case "tool_output_truncated" -> observeTruncation(requestId, event.metadata());
            case "context_usage" -> observeContextUsage(requestId, event.metadata());
            case "context_compacted" -> observeCompaction(requestId, event.metadata());
            case "mutation_approval_batch_required" -> approvalStarted(requestId, event.metadata());
            case "mutation_approval_decision_accepted" -> approvalCompleted(requestId, event.metadata());
            case "elicitation_required" -> elicitationStarted(requestId, event.metadata());
            case "elicitation_decision_accepted", "elicitation_decision_rejected" ->
                    elicitationCompleted(requestId, subtype, event.metadata());
            default -> {
                if (workflowEvent(subtype)) observeWorkflow(requestId, subtype, event.metadata());
            }
        }
    }

    void closeRequest(String requestId, String requestOutcome) {
        workflows.closeMatching(key -> key.requestId.equals(requestId),
                operation -> operation.finish(requestOutcome, true));
        tools.closeMatching(key -> key.requestId.equals(requestId),
                operation -> operation.finish(requestOutcome, true));
        approvals.closeMatching(key -> key.requestId.equals(requestId), started ->
            instruments.approvalWait.record(elapsedMillis(started),
                    Attributes.builder().put("score.ai.approval.type", "mutation")
                            .put("score.ai.approval.outcome", outcome(requestOutcome)).build()));
        elicitations.closeMatching(key -> key.requestId.equals(requestId), started ->
            instruments.approvalWait.record(elapsedMillis(started),
                    Attributes.builder().put("score.ai.approval.type", "elicitation")
                            .put("score.ai.approval.outcome", outcome(requestOutcome)).build()));
    }

    Context workflowContext(String requestId, String nodeId) {
        if (requestId == null || nodeId == null) return null;
        TimedSpan operation = workflows.active(new OperationKey(requestId, nodeId));
        return operation != null ? operation.context : null;
    }

    Context toolContext(String requestId, String toolCallId) {
        if (requestId == null || toolCallId == null) return null;
        TimedSpan operation = tools.active(new OperationKey(requestId, toolCallId));
        return operation != null ? operation.context : null;
    }

    private void observeProviderRetry(String requestId, Map<String, Object> metadata) {
        String statusClass = statusClass(number(metadata.get("status_code")));
        String failure = boundedType(metadata.get("failure_class"));
        Attributes attributes = Attributes.builder()
                .put("score.ai.provider.status_class", statusClass)
                .put("error.type", failure)
                .build();
        instruments.providerRetries.add(1, attributes);
        long delay = number(metadata.get("delay_millis"));
        if (delay >= 0) instruments.providerRetryDelay.record(delay, attributes);
        Span root = Span.fromContext(parents.apply(requestId));
        root.addEvent("score.ai.provider.retry", Attributes.builder()
                .put("score.ai.attempt", number(metadata.get("attempt")))
                .put("score.ai.max_attempts", number(metadata.get("max_attempts")))
                .put("score.ai.retry_delay_ms", Math.max(0L, delay))
                .put("score.ai.provider.status_class", statusClass)
                .put("error.type", failure)
                .build());
    }

    private void observeTruncation(String requestId, Map<String, Object> metadata) {
        String tool = ScoreAiObservability.value(Objects.toString(metadata.get("toolName"), null));
        String source = Boolean.TRUE.equals(metadata.get("mcp")) ? "mcp" : "local";
        instruments.toolTruncations.add(1, Attributes.builder()
                .put("score.ai.tool.source", source).build());
        Span.fromContext(parents.apply(requestId)).addEvent("score.ai.tool.output.truncated",
                Attributes.builder()
                        .put("gen_ai.tool.name", tool)
                        .put("score.ai.tool.original_bytes", number(metadata.get("originalUtf8Bytes")))
                        .put("score.ai.tool.returned_bytes", number(metadata.get("returnedUtf8Bytes")))
                        .build());
    }

    private void observeContextUsage(String requestId, Map<String, Object> metadata) {
        Object value = metadata.get("contextUsage");
        if (value instanceof AiContextUsageInfo usage) {
            Attributes attributes = Attributes.builder()
                    .put("gen_ai.request.model", ScoreAiObservability.value(usage.modelName()))
                    .put("score.ai.context.estimated", usage.estimated())
                    .build();
            instruments.contextWindowUsage.record(usage.usedPercent(), attributes);
            Span.fromContext(parents.apply(requestId))
                    .setAttribute("score.ai.context_window.used_percent", usage.usedPercent())
                    .setAttribute("score.ai.context_window.input_tokens", usage.currentInputTokens())
                    .setAttribute("score.ai.context_window.limit", usage.contextWindow());
        }
    }

    private void observeCompaction(String requestId, Map<String, Object> metadata) {
        String reason = ScoreAiObservability.value(Objects.toString(metadata.get("reason"), null));
        Attributes attributes = Attributes.builder()
                .put("score.ai.compaction.reason", reason)
                .put("score.ai.compaction.automatic", Boolean.TRUE.equals(metadata.get("automatic")))
                .build();
        instruments.compactions.add(1, attributes);
        Span.fromContext(parents.apply(requestId)).addEvent("score.ai.context.compacted", attributes);
    }

    private void approvalStarted(String requestId, Map<String, Object> metadata) {
        String batchId = ScoreAiObservability.value(Objects.toString(metadata.get("batchId"), null));
        boolean accepted = approvals.startIfAbsent(
                new OperationKey(requestId, batchId), System::nanoTime);
        if (accepted) {
            Span.fromContext(parents.apply(requestId)).addEvent("score.ai.approval.requested");
        }
    }

    private void approvalCompleted(String requestId, Map<String, Object> metadata) {
        String batchId = ScoreAiObservability.value(Objects.toString(metadata.get("batchId"), null));
        Long started = approvals.terminate(
                new OperationKey(requestId, batchId), System::nanoTime);
        if (started == null) return;
        String result = number(metadata.get("denied")) > 0 ? "partially_denied" : "approved";
        instruments.approvalWait.record(elapsedMillis(started),
                Attributes.builder().put("score.ai.approval.type", "mutation")
                        .put("score.ai.approval.outcome", result).build());
        Span.fromContext(parents.apply(requestId)).addEvent("score.ai.approval.decided",
                Attributes.builder()
                        .put("score.ai.approval.approved", Math.max(0L, number(metadata.get("approved"))))
                        .put("score.ai.approval.denied", Math.max(0L, number(metadata.get("denied"))))
                        .build());
    }

    private void elicitationStarted(String requestId, Map<String, Object> metadata) {
        String id = ScoreAiObservability.value(Objects.toString(metadata.get("elicitationId"), null));
        boolean accepted = elicitations.startIfAbsent(
                new OperationKey(requestId, id), System::nanoTime);
        if (accepted) {
            Span.fromContext(parents.apply(requestId)).addEvent("score.ai.elicitation.requested");
        }
    }

    private void elicitationCompleted(String requestId, String subtype, Map<String, Object> metadata) {
        String id = ScoreAiObservability.value(Objects.toString(metadata.get("elicitationId"), null));
        Long started = elicitations.terminate(
                new OperationKey(requestId, id), System::nanoTime);
        if (started == null) return;
        String result = subtype.endsWith("accepted") ? "accepted" : "rejected";
        instruments.approvalWait.record(elapsedMillis(started),
                Attributes.builder().put("score.ai.approval.type", "elicitation")
                        .put("score.ai.approval.outcome", result).build());
        Span.fromContext(parents.apply(requestId)).addEvent("score.ai.elicitation." + result);
    }

    private void observeWorkflow(String requestId, String subtype, Map<String, Object> metadata) {
        String lifecycle = terminalSuffix(subtype);
        String workflow = ScoreAiObservability.value(Objects.toString(
                metadata.getOrDefault("workflow", workflowPrefix(subtype)), null));
        String operationId = ScoreAiObservability.value(Objects.toString(
                metadata.getOrDefault("node_id", metadata.get("fanout_id")), workflowPrefix(subtype)));
        OperationKey key = new OperationKey(requestId, operationId);
        if ("started".equals(lifecycle) || "planned".equals(lifecycle)
                || "synthesizing".equals(lifecycle)) {
            workflows.start(key, () -> {
                Span span = tracer.spanBuilder("score.ai.workflow")
                        .setParent(parents.apply(requestId))
                        .setAttribute("score.ai.workflow.name", workflow)
                        .setAttribute("score.ai.workflow.run_id", operationId)
                        .setAttribute("score.ai.workflow.kind", workflowPrefix(subtype))
                        .startSpan();
                recordWorkflowShape(workflow, metadata);
                return new TimedSpan(span, parents.apply(requestId), System.nanoTime(),
                        workflowMetricName(workflow), true);
            });
            return;
        }
        TimedSpan operation = workflows.terminate(key, () -> {
            Span span = tracer.spanBuilder("score.ai.workflow")
                    .setParent(parents.apply(requestId))
                    .setAttribute("score.ai.workflow.name", workflow)
                    .setAttribute("score.ai.workflow.run_id", operationId)
                    .setAttribute("score.ai.workflow.kind", workflowPrefix(subtype))
                    .startSpan();
            return new TimedSpan(span, parents.apply(requestId), System.nanoTime(),
                    workflowMetricName(workflow), true);
        });
        if (operation == null) return;
        String result = outcome(lifecycle);
        boolean partial = "success".equals(result) && hasFailures(metadata);
        operation.span.setAttribute("score.ai.workflow.partial_failure", partial);
        operation.finish(partial ? "partial_failure" : result, false);
    }

    private void recordWorkflowShape(String workflow, Map<String, Object> metadata) {
        Attributes attributes = Attributes.builder()
                .put("score.ai.workflow.name", workflowMetricName(workflow)).build();
        long agents = firstPositive(metadata, "agent_count", "max_agents", "worker_count");
        if (agents > 0) instruments.workflowFanout.add(agents, attributes);
        long iteration = firstPositive(metadata, "workflow_iteration", "iteration");
        if (iteration > 0) instruments.workflowIterations.add(1, attributes);
    }

    private void observeTool(String requestId, String subtype, AiExecutionLifecycle event) {
        String callId = ScoreAiObservability.value(event.toolCallId());
        String tool = ScoreAiObservability.value(event.toolName());
        boolean mcp = Boolean.TRUE.equals(event.metadata().get("mcp"));
        String mcpServerName = ScoreAiObservability.value(
                Objects.toString(event.metadata().get("mcp_server_name"), null));
        OperationKey key = new OperationKey(requestId, callId);
        if ("started".equals(subtype)) {
            tools.start(key, () -> new TimedSpan(
                    startToolSpan(requestId, tool, callId, mcp, mcpServerName), parents.apply(requestId),
                    System.nanoTime(), mcp ? "mcp" : "local", false));
            return;
        }
        TimedSpan operation = tools.terminate(key, () ->
                new TimedSpan(startToolSpan(requestId, tool, callId, mcp, mcpServerName),
                    parents.apply(requestId), System.nanoTime(),
                    mcp ? "mcp" : "local", false));
        if (operation == null) return;
        long recordedDuration = number(event.metadata().get("duration_ms"));
        if (recordedDuration >= 0) operation.recordedDurationMillis = recordedDuration;
        if (Boolean.TRUE.equals(event.metadata().get("result_truncated"))) {
            operation.span.setAttribute("score.ai.tool.result_truncated", true);
        }
        Object failure = event.metadata().get("failure_type");
        if (failure != null) operation.span.setAttribute("error.type", boundedType(failure));
        operation.finish(outcome(subtype), false);
    }

    private Span startToolSpan(String requestId, String tool, String callId, boolean mcp,
                               String mcpServerName) {
        var builder = tracer.spanBuilder("score.ai.tool")
                .setParent(parents.apply(requestId))
                .setAttribute("gen_ai.operation.name", "execute_tool")
                .setAttribute("gen_ai.tool.name", tool)
                .setAttribute("gen_ai.tool.call.id", callId);
        if (mcp) {
            builder.setSpanKind(SpanKind.CLIENT)
                    .setAttribute("rpc.system", "jsonrpc")
                    .setAttribute("rpc.method", "tools/call")
                    .setAttribute("mcp.server.name", mcpServerName)
                    .setAttribute("mcp.method.name", "tools/call")
                    .setAttribute("mcp.protocol.name", "streamable-http");
        }
        return builder.startSpan();
    }

    private static boolean workflowEvent(String subtype) {
        return subtype.startsWith("multi_agent_") || subtype.startsWith("subagent_")
                || subtype.startsWith("parallel_workflow_") || subtype.startsWith("parallel_task_");
    }

    private static String terminalSuffix(String subtype) {
        for (String suffix : new String[]{
                "started", "planned", "synthesizing", "completed", "failed", "cancelled"}) {
            if (subtype.endsWith("_" + suffix)) return suffix;
        }
        return "unknown";
    }

    private static String workflowPrefix(String subtype) {
        String suffix = terminalSuffix(subtype);
        return subtype.endsWith("_" + suffix)
                ? subtype.substring(0, subtype.length() - suffix.length() - 1) : subtype;
    }

    private static String workflowMetricName(String workflow) {
        String normalized = AiObservationInstruments.normalized(workflow);
        return switch (normalized) {
            case "direct", "chain", "parallel", "routing", "orchestrator_workers" -> normalized;
            default -> "unknown";
        };
    }

    static String outcome(String status) {
        String normalized = status != null ? status.strip().toLowerCase() : "unknown";
        return switch (normalized) {
            case "completed", "complete", "success" -> "success";
            case "timed_out", "timeout" -> "timeout";
            case "cancelled", "canceled" -> "cancelled";
            case "denied", "blocked", "refused" -> "refused";
            case "failed", "error", "partial_failure" -> normalized;
            case "admission_rejected" -> "admission_rejected";
            case "unknown_reconciliation_required" -> "error";
            default -> "unknown";
        };
    }

    private static boolean hasFailures(Map<String, Object> metadata) {
        return firstPositive(metadata, "failed", "failed_count", "failure_count",
                "failed_agents") > 0;
    }

    private static long firstPositive(Map<String, Object> metadata, String... names) {
        for (String name : names) {
            long value = number(metadata.get(name));
            if (value > 0) return value;
        }
        return 0;
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value != null ? Long.parseLong(value.toString()) : -1L; }
        catch (NumberFormatException ignored) { return -1L; }
    }

    private static String boundedType(Object value) {
        String type = Objects.toString(value, "unknown").strip();
        return type.matches("[A-Za-z0-9_.$-]{1,120}") ? type : "unknown";
    }

    private static String statusClass(long status) {
        return status >= 100 && status <= 599 ? (status / 100) + "xx" : "unknown";
    }

    private static double elapsedMillis(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)).toNanos()
                / 1_000_000.0;
    }

    private record OperationKey(String requestId, String operationId) { }

    private final class TimedSpan {
        private final Span span;
        private final long startedNanos;
        private final String metricName;
        private final boolean workflow;
        private final Context context;
        private final AtomicBoolean ended = new AtomicBoolean();
        private double recordedDurationMillis = -1;

        private TimedSpan(Span span, Context parent, long startedNanos,
                          String metricName, boolean workflow) {
            this.span = span;
            this.startedNanos = startedNanos;
            this.metricName = metricName;
            this.workflow = workflow;
            this.context = ScoreAiObservability.privateContext(parent, span);
        }

        private void finish(String result, boolean abandoned) {
            if (!ended.compareAndSet(false, true)) return;
            String normalized = outcome(result);
            span.setAttribute("score.ai.outcome", normalized);
            if (abandoned) span.setAttribute("score.ai.observation.incomplete", true);
            if (!"success".equals(normalized)) span.setStatus(StatusCode.ERROR, normalized);
            double duration = recordedDurationMillis >= 0
                    ? recordedDurationMillis : elapsedMillis(startedNanos);
            AttributesBuilder labels = Attributes.builder().put(
                    workflow ? "score.ai.workflow.name" : "score.ai.tool.source", metricName)
                    .put("score.ai.outcome", normalized);
            if (workflow) {
                instruments.workflows.add(1, labels.build());
                instruments.workflowDuration.record(duration, labels.build());
            } else {
                instruments.toolCalls.add(1, labels.build());
                instruments.toolDuration.record(duration, labels.build());
            }
            span.end();
        }
    }
}

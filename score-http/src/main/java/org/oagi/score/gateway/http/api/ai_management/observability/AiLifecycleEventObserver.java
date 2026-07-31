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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.BiFunction;

import static org.oagi.score.gateway.http.api.ai_management.observability.AiLifecycleMetadata.*;

/** Converts existing trajectory lifecycle events into content-free spans and metrics. */
final class AiLifecycleEventObserver {

    private final Tracer tracer;
    private final AiObservationInstruments instruments;
    private final Function<String, Context> parents;
    private final BiFunction<String, String, Context> explicitParents;
    private final Function<String, String> agents;
    private final AiLifecycleOperationRegistry<OperationKey, TimedSpan> workflows =
            new AiLifecycleOperationRegistry<>();
    private final AiLifecycleOperationRegistry<OperationKey, TimedSpan> tools =
            new AiLifecycleOperationRegistry<>();
    private final AiLifecycleOperationRegistry<OperationKey, Long> approvals =
            new AiLifecycleOperationRegistry<>();
    private final AiLifecycleOperationRegistry<OperationKey, Long> elicitations =
            new AiLifecycleOperationRegistry<>();

    AiLifecycleEventObserver(Tracer tracer, AiObservationInstruments instruments,
                             Function<String, Context> parents,
                             Function<String, String> agents,
                             BiFunction<String, String, Context> explicitParents) {
        this.tracer = tracer;
        this.instruments = instruments;
        this.parents = parents;
        this.agents = agents;
        this.explicitParents = explicitParents;
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
            case "change_approval_batch_required" -> approvalStarted(requestId, event.metadata());
            case "change_approval_decision_accepted" -> approvalCompleted(requestId, event.metadata());
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
            instruments.approvalWait.record(AiObservationTiming.elapsedMillis(started),
                    Attributes.builder().put("score.ai.approval.type", "change")
                            .put("score.ai.approval.outcome", outcome(requestOutcome)).build()));
        elicitations.closeMatching(key -> key.requestId.equals(requestId), started ->
            instruments.approvalWait.record(AiObservationTiming.elapsedMillis(started),
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
        instruments.approvalWait.record(AiObservationTiming.elapsedMillis(started),
                Attributes.builder().put("score.ai.approval.type", "change")
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
        instruments.approvalWait.record(AiObservationTiming.elapsedMillis(started),
                Attributes.builder().put("score.ai.approval.type", "elicitation")
                        .put("score.ai.approval.outcome", result).build());
        Span.fromContext(parents.apply(requestId)).addEvent("score.ai.elicitation." + result);
    }

    private void observeWorkflow(String requestId, String subtype, Map<String, Object> metadata) {
        if (implicitRootQueue(metadata)) return;
        String lifecycle = terminalSuffix(subtype);
        String selectedWorkflow = ScoreAiObservability.value(Objects.toString(
                metadata.getOrDefault("workflow", workflowPrefix(subtype)), null));
        String workflowKind = workflowPrefix(subtype);
        String workflowType = workflowType(metadata);
        String semanticWorkflow = workflowMetricName(selectedWorkflow);
        String operationId = ScoreAiObservability.value(Objects.toString(
                metadata.getOrDefault("node_id", metadata.get("fanout_id")), workflowPrefix(subtype)));
        Context parent = workflowParent(requestId, metadata);
        OperationKey key = new OperationKey(requestId, operationId);
        if ("started".equals(lifecycle) || "planned".equals(lifecycle)
                || "synthesizing".equals(lifecycle)) {
            workflows.start(key, () -> {
                var builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                                GenAiSemanticConventions.INVOKE_WORKFLOW, semanticWorkflow))
                        .setParent(parent)
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("gen_ai.operation.name",
                                GenAiSemanticConventions.INVOKE_WORKFLOW)
                        .setAttribute("gen_ai.workflow.name", semanticWorkflow)
                        .setAttribute(GenAiSemanticConventions.WORKFLOW_NESTED, true)
                        .setAttribute("score.ai.workflow.name", selectedWorkflow)
                        .setAttribute("score.ai.workflow.run_id", operationId)
                        .setAttribute("score.ai.workflow.id", selectedWorkflow)
                        .setAttribute("score.ai.workflow.kind", workflowKind)
                        .setAttribute("score.ai.workflow.type", workflowType);
                setEventIdentity(builder, metadata);
                setWorkflowShapeAttributes(builder, metadata);
                Span span = builder.startSpan();
                recordWorkflowShape(selectedWorkflow, metadata);
                return new TimedSpan(span, parent, System.nanoTime(),
                        workflowMetricName(selectedWorkflow), selectedWorkflow, null, true);
            });
            return;
        }
        TimedSpan operation = workflows.terminate(key, () -> {
            var builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                            GenAiSemanticConventions.INVOKE_WORKFLOW, semanticWorkflow))
                    .setParent(parent)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("gen_ai.operation.name",
                            GenAiSemanticConventions.INVOKE_WORKFLOW)
                    .setAttribute("gen_ai.workflow.name", semanticWorkflow)
                    .setAttribute(GenAiSemanticConventions.WORKFLOW_NESTED, true)
                    .setAttribute("score.ai.workflow.name", selectedWorkflow)
                    .setAttribute("score.ai.workflow.run_id", operationId)
                    .setAttribute("score.ai.workflow.id", selectedWorkflow)
                    .setAttribute("score.ai.workflow.kind", workflowKind)
                    .setAttribute("score.ai.workflow.type", workflowType);
            setEventIdentity(builder, metadata);
            setWorkflowShapeAttributes(builder, metadata);
            Span span = builder.startSpan();
            return new TimedSpan(span, parent, System.nanoTime(),
                    workflowMetricName(selectedWorkflow), selectedWorkflow, null, true);
        });
        if (operation == null) return;
        String result = outcome(lifecycle);
        boolean partial = "success".equals(result) && hasFailures(metadata);
        setTerminalEventIdentity(operation.span, metadata);
        setTerminalWorkflowCounts(operation.span, metadata);
        operation.span.setAttribute("score.ai.workflow.partial_failure", partial);
        operation.finish(partial ? "partial_failure" : result, false);
    }

    private void setTerminalWorkflowCounts(Span span, Map<String, Object> metadata) {
        long completed = number(metadata.get("completed"));
        if (completed >= 0) span.setAttribute("score.ai.workflow.completed", completed);
        long failed = number(metadata.get("failed"));
        if (failed >= 0) span.setAttribute("score.ai.workflow.failed", failed);
        long failureCount = number(metadata.get("failure_count"));
        if (failureCount >= 0) {
            span.setAttribute("score.ai.workflow.failure_count", failureCount);
        }
    }

    private void recordWorkflowShape(String workflow, Map<String, Object> metadata) {
        Attributes attributes = Attributes.builder()
                .put("score.ai.workflow.name", workflowMetricName(workflow)).build();
        long agents = firstPositive(metadata, "member_count", "agent_count", "max_agents", "worker_count");
        if (agents > 0) instruments.workflowFanout.add(agents, attributes);
        long iteration = firstPositive(metadata, "workflow_iteration", "iteration");
        if (iteration > 0) instruments.workflowIterations.add(1, attributes);
    }

    private void setWorkflowShapeAttributes(io.opentelemetry.api.trace.SpanBuilder builder,
                                            Map<String, Object> metadata) {
        long depth = number(metadata.get("depth"));
        if (depth >= 0) builder.setAttribute("score.ai.workflow.depth", depth);
        long members = number(metadata.get("member_count"));
        if (members >= 0) builder.setAttribute("score.ai.workflow.member_count", members);
    }

    private void observeTool(String requestId, String subtype, AiExecutionLifecycle event) {
        String callId = ScoreAiObservability.value(event.toolCallId());
        String tool = ScoreAiObservability.value(event.toolName());
        boolean mcp = Boolean.TRUE.equals(event.metadata().get("mcp"));
        String agent = agents.apply(requestId);
        String explicitParentId = Objects.toString(
                event.metadata().getOrDefault("agent_run_id", event.metadata().get("node_id")), null);
        Context explicitParent = explicitParents.apply(requestId, explicitParentId);
        Context parent = explicitParent != null ? explicitParent : parents.apply(requestId);
        OperationKey key = new OperationKey(requestId, callId);
        if ("started".equals(subtype)) {
            tools.start(key, () -> new TimedSpan(
                    startToolSpan(parent, tool, callId, agent, mcp, event.metadata()),
                    parent, System.nanoTime(), mcp ? "mcp" : "local",
                    tool, agent, false));
            return;
        }
        TimedSpan operation = tools.terminate(key, () ->
                new TimedSpan(startToolSpan(parent, tool, callId, agent, mcp, event.metadata()),
                    parent, System.nanoTime(), mcp ? "mcp" : "local",
                    tool, agent, false));
        if (operation == null) return;
        if (Boolean.TRUE.equals(event.metadata().get("result_truncated"))) {
            operation.span.setAttribute("score.ai.tool.result_truncated", true);
        }
        Object failure = event.metadata().get("failure_type");
        if (failure != null) {
            operation.errorType = boundedType(failure);
            operation.span.setAttribute("error.type", operation.errorType);
        }
        setTerminalEventIdentity(operation.span, event.metadata());
        operation.finish(outcome(subtype), false);
    }

    private Span startToolSpan(Context parent, String tool, String callId, String agent,
                               boolean mcp,
                               Map<String, Object> metadata) {
        var builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                        GenAiSemanticConventions.EXECUTE_TOOL, tool))
                .setParent(parent)
                // This is the existing outer GenAI tool-execution span. MCP instrumentation
                // enriches it instead of creating a separate transport CLIENT span.
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.EXECUTE_TOOL)
                .setAttribute("gen_ai.tool.name", tool)
                .setAttribute("gen_ai.tool.type", "function")
                .setAttribute("gen_ai.tool.call.id", callId);
        setEventIdentity(builder, metadata);
        setStringAttribute(builder, "gen_ai.agent.name", agent);
        if (mcp) {
            builder.setAttribute("mcp.method.name", "tools/call");
            setStringAttribute(builder, "score.ai.mcp.server.name", metadata.get("mcp_server_name"));
            setStringAttribute(builder, "mcp.protocol.version", metadata.get("mcp_protocol_version"));
            setStringAttribute(builder, "network.protocol.name", metadata.get("network_protocol_name"));
            setStringAttribute(builder, "network.transport", metadata.get("network_transport"));
            setStringAttribute(builder, "server.address", metadata.get("server_address"));
            long port = number(metadata.get("server_port"));
            if (port > 0 && port <= 65_535) builder.setAttribute("server.port", port);
        }
        return builder.startSpan();
    }

    private Context workflowParent(String requestId, Map<String, Object> metadata) {
        String parentId = Objects.toString(metadata.get("parent_node_id"), null);
        Context parent = explicitParents.apply(requestId, parentId);
        return parent != null ? parent : parents.apply(requestId);
    }

    static String outcome(String status) {
        return AiLifecycleMetadata.outcome(status);
    }

    private record OperationKey(String requestId, String operationId) { }

    private final class TimedSpan {
        private final Span span;
        private final long startedNanos;
        private final String metricName;
        private final String semanticTarget;
        private final String semanticAgent;
        private final boolean workflow;
        private final Context context;
        private final AtomicBoolean ended = new AtomicBoolean();
        private String errorType;

        private TimedSpan(Span span, Context parent, long startedNanos,
                          String metricName, String semanticTarget, String semanticAgent,
                          boolean workflow) {
            this.span = span;
            this.startedNanos = startedNanos;
            this.metricName = metricName;
            this.semanticTarget = semanticTarget;
            this.semanticAgent = semanticAgent;
            this.workflow = workflow;
            this.context = ScoreAiObservability.privateContext(parent, span);
        }

        private void finish(String result, boolean abandoned) {
            if (!ended.compareAndSet(false, true)) return;
            String normalized = outcome(result);
            span.setAttribute("score.ai.outcome", normalized);
            if (abandoned) span.setAttribute("score.ai.observation.incomplete", true);
            if (errorType == null && !"success".equals(normalized)
                    && !"cancelled".equals(normalized)) errorType = normalized;
            if (errorType != null) {
                span.setAttribute("error.type", errorType);
                span.setStatus(StatusCode.ERROR, normalized);
            }
            double duration = AiObservationTiming.elapsedMillis(startedNanos);
            AttributesBuilder labels = Attributes.builder().put(
                    workflow ? "score.ai.workflow.name" : "score.ai.tool.source", metricName)
                    .put("score.ai.outcome", normalized);
            if (workflow) {
                instruments.workflows.add(1, labels.build());
                instruments.workflowDuration.record(duration, labels.build());
                instruments.genAiWorkflowDuration.record(
                        AiObservationTiming.elapsedSeconds(startedNanos),
                        GenAiSemanticConventions.workflowDurationAttributes(
                                semanticTarget, errorType, true));
            } else {
                instruments.toolCalls.add(1, labels.build());
                instruments.toolDuration.record(duration, labels.build());
                instruments.genAiExecuteToolDuration.record(
                        AiObservationTiming.elapsedSeconds(startedNanos),
                        GenAiSemanticConventions.toolDurationAttributes(
                                semanticTarget, semanticAgent, errorType));
            }
            span.end();
        }
    }
}

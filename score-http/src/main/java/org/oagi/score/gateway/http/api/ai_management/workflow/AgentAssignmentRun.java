package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentAssignmentLifecycle;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutputRetryHandoffException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Owns lifecycle and exact-once usage bookkeeping for one assigned Agent run. */
final class AgentAssignmentRun {

    private AgentAssignmentRun() {
    }

    static void preflightStarted(Agent agent, AgentWorkflowContext context) {
        if (context.assignment() == null) return;
        AiWorkflowPlan.AgentTask task = context.assignment();
        Map<String, Object> namespace = AgentAssignmentLifecycle.namespace(agent, context, task);
        context.execution().recorder().lifecycle("subagent_preparing",
                "Preparing " + task.label() + ".",
                AgentAssignmentLifecycle.metadata(namespace, "preparing"));
    }

    static Lifecycle lifecycle(Agent agent, AgentWorkflowContext context,
                               AgentRunRequest request) {
        if (context.assignment() == null) return Lifecycle.noop();
        AiWorkflowPlan.AgentTask task = Objects.requireNonNull(
                context.assignment(), "Agent assignment");
        AgentExecutionRecorder recorder = executionRecorder(context, request);
        boolean terminal = request instanceof AgentRunRequest.Chat
                && recorder != context.execution().recorder();
        Map<String, Object> namespace = AgentAssignmentLifecycle.namespace(agent, context, task);
        return new Lifecycle(
                () -> recorder.lifecycle("subagent_started",
                        AgentAssignmentLifecycle.status(task, false),
                        AgentAssignmentLifecycle.metadata(namespace, "started")),
                result -> completed(recorder, terminal, task, namespace, result),
                () -> recorder.lifecycle("subagent_retry",
                        "Revising " + task.label() + " after output policy feedback.",
                        AgentAssignmentLifecycle.metadata(namespace, "retrying")),
                failure -> failed(recorder, terminal, task, namespace, failure));
    }

    static UsageSource registerUsage(Agent agent, AgentWorkflowContext context,
                                     AgentRunRequest request) {
        if (!(request instanceof AgentRunRequest.Chat chat)) {
            return null;
        }
        AgentExecutionRecorder child = chat.context().recorder();
        boolean rootRecorder = child == context.execution().recorder();
        UsageSource source = new UsageSource(
                child, context.location() != null ? context.location().nodeId() : null,
                agent.definition().name());
        context.registerAttemptUsage(source::snapshot, () -> {
            source.close();
            // The outer ChatService owns the root disclosure recorder through commit.
            // Child recorders, by contrast, terminate with their assigned call.
            if (!rootRecorder) child.sealAgainstLateCallbacks();
            child.sealUsageAccounting();
        });
        return source;
    }

    static void failPreflight(Agent agent, AgentWorkflowContext context,
                              Lifecycle lifecycle, RuntimeException failure) {
        if (lifecycle != null) {
            lifecycle.failed().accept(failure);
            return;
        }
        if (context.assignment() == null) return;
        AiWorkflowPlan.AgentTask task = context.assignment();
        Map<String, Object> namespace = AgentAssignmentLifecycle.namespace(agent, context, task);
        context.execution().recorder().lifecycle("subagent_preflight_failed",
                "Could not prepare " + task.label() + ".",
                AgentAssignmentLifecycle.metadata(namespace, "failed", Map.of(
                        "reason", failure.getClass().getSimpleName())));
    }

    private static void completed(AgentExecutionRecorder recorder, boolean terminal,
                                  AiWorkflowPlan.AgentTask task,
                                  Map<String, Object> namespace, AgentRunResult result) {
        Map<String, Object> metadata = result != null
                ? new LinkedHashMap<>(result.metadata().attributes()) : new LinkedHashMap<>();
        metadata.putAll(AgentAssignmentLifecycle.metadata(namespace, "completed"));
        recordLifecycle(recorder, terminal, "subagent_completed",
                AgentAssignmentLifecycle.status(task, true), metadata);
    }

    private static void failed(AgentExecutionRecorder recorder, boolean terminal,
                               AiWorkflowPlan.AgentTask task, Map<String, Object> namespace,
                               RuntimeException failure) {
        if (failure instanceof AgentOutputRetryHandoffException) {
            recordLifecycle(recorder, terminal, "subagent_output_retry_handoff",
                    "Regenerating " + task.label() + " without tools.",
                    AgentAssignmentLifecycle.metadata(namespace, "retry_handoff"));
        } else if (failure instanceof CancellationException) {
            recordLifecycle(recorder, terminal, "subagent_cancelled",
                    "Stopped " + task.label() + ".",
                    AgentAssignmentLifecycle.metadata(namespace, "cancelled"));
        } else if (failure instanceof AgentGuardrailRefusedException) {
            recordLifecycle(recorder, terminal, "subagent_refused",
                    "Policy refused " + task.label() + ".",
                    AgentAssignmentLifecycle.metadata(namespace, "refused"));
        } else {
            recordLifecycle(recorder, terminal, "subagent_failed",
                    "Could not complete " + task.label() + ".",
                    AgentAssignmentLifecycle.metadata(namespace, "failed", Map.of(
                            "reason", failure.getClass().getSimpleName())));
        }
    }

    private static AgentExecutionRecorder executionRecorder(
            AgentWorkflowContext context, AgentRunRequest request) {
        return request instanceof AgentRunRequest.Chat chat
                ? chat.context().recorder() : context.execution().recorder();
    }

    private static void recordLifecycle(AgentExecutionRecorder recorder, boolean terminal,
                                        String subtype, String content,
                                        Map<String, Object> metadata) {
        if (terminal) recorder.terminalLifecycle(subtype, content, metadata);
        else recorder.lifecycle(subtype, content, metadata);
    }

    record Lifecycle(Runnable started, Consumer<AgentRunResult> completed,
                     Runnable retried, Consumer<RuntimeException> failed) {
        private static Lifecycle noop() {
            return new Lifecycle(() -> { }, result -> { }, () -> { }, failure -> { });
        }
    }

    /** Reconciles transport deltas and trajectory totals without adding both. */
    static final class UsageSource {
        private final AgentExecutionRecorder recorder;
        private final AiUsageSnapshot baseline;
        private String usageNodeId;
        private String usageAgentName;
        private long promptTokens;
        private long completionTokens;
        private long modelCalls;
        private boolean completed;
        private boolean closed;

        private UsageSource(AgentExecutionRecorder recorder, String nodeId, String agentName) {
            this.recorder = recorder;
            this.baseline = recorder.usageSnapshot();
            this.usageNodeId = baseline != null && baseline.nodeId() != null
                    ? baseline.nodeId() : nodeId;
            this.usageAgentName = baseline != null && baseline.agentName() != null
                    ? baseline.agentName() : agentName;
        }

        synchronized void record(AgentRunResult result) {
            if (closed) return;
            result.usage().ifPresent(usage -> {
                promptTokens += usage.inputTokens();
                completionTokens += usage.outputTokens();
                modelCalls += usage.modelCalls();
            });
        }

        private synchronized AiUsageSnapshot snapshot() {
            if (!completed) mergeObservedUsage();
            if (modelCalls == 0L) return null;
            return new AiUsageSnapshot(
                    usageNodeId, usageAgentName,
                    promptTokens, completionTokens, modelCalls);
        }

        synchronized void complete() {
            if (completed || closed) return;
            mergeObservedUsage();
            completed = true;
        }

        private synchronized void close() {
            if (!completed) {
                mergeObservedUsage();
                completed = true;
            }
            closed = true;
        }

        private void mergeObservedUsage() {
            AiUsageSnapshot observed = recorder.usageSnapshot();
            if (observed == null) return;
            if (observed.nodeId() != null) usageNodeId = observed.nodeId();
            if (observed.agentName() != null) usageAgentName = observed.agentName();
            promptTokens = Math.max(promptTokens,
                    delta(observed.promptTokens(), baseline != null
                            ? baseline.promptTokens() : 0L));
            completionTokens = Math.max(completionTokens,
                    delta(observed.completionTokens(), baseline != null
                            ? baseline.completionTokens() : 0L));
            modelCalls = Math.max(modelCalls,
                    delta(observed.modelCalls(), baseline != null
                            ? baseline.modelCalls() : 0L));
        }

        private long delta(long current, long initial) {
            return Math.max(0L, current - initial);
        }
    }
}

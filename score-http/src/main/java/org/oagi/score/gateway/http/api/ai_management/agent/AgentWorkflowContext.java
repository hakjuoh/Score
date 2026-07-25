package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Immutable input supplied to every Agent invoked by a Workflow. */
public record AgentWorkflowContext(
        AgentExecutionContext execution,
        Request request,
        List<WorkflowResult> inputs,
        AiWorkflowPlan workflow,
        String assignmentId,
        AiWorkflowPlan.AgentTask assignment,
        AgentOutput candidate,
        List<AiWorkflowFeedback> feedback,
        int iteration,
        int maximumIterations,
        Location location,
        WorkflowRunControl runControl) {

    public AgentWorkflowContext {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(request, "request");
        inputs = inputs != null ? List.copyOf(inputs) : List.of();
        feedback = feedback != null ? List.copyOf(feedback) : List.of();
        if ((assignmentId == null) != (assignment == null)) {
            throw new IllegalArgumentException(
                    "assignmentId and assignment must be supplied together");
        }
        if (iteration < 0) throw new IllegalArgumentException("iteration must not be negative");
        if (maximumIterations < 1) {
            throw new IllegalArgumentException("maximumIterations must be positive");
        }
        runControl = runControl != null ? runControl : WorkflowRunControl.NOOP;
    }

    public static AgentWorkflowContext root(AgentExecutionContext execution, Request request,
                                            int maximumIterations) {
        return root(execution, request, maximumIterations, WorkflowRunControl.NOOP);
    }

    public static AgentWorkflowContext root(AgentExecutionContext execution, Request request,
                                            int maximumIterations,
                                            WorkflowRunControl runControl) {
        return new AgentWorkflowContext(execution, request, List.of(), null, null,
                null, null, List.of(), 0, maximumIterations, null, runControl);
    }

    public AgentWorkflowContext withAssignment(AiWorkflowPlan plan,
                                               String memberId,
                                               AiWorkflowPlan.AgentTask task,
                                               List<WorkflowResult> upstream) {
        return new AgentWorkflowContext(execution, request, upstream, plan,
                Objects.requireNonNull(memberId, "memberId"), task, candidate,
                feedback, iteration, maximumIterations, location, runControl);
    }

    public AgentWorkflowContext inWorkflow(AiWorkflowPlan plan, Location value) {
        return new AgentWorkflowContext(execution, request, inputs, plan, null, null,
                candidate, feedback, iteration, maximumIterations,
                Objects.requireNonNull(value, "location"), runControl);
    }

    public AgentWorkflowContext withInputs(List<WorkflowResult> value) {
        return new AgentWorkflowContext(execution, request, value, workflow,
                assignmentId, assignment, candidate, feedback, iteration,
                maximumIterations, location, runControl);
    }

    public AgentWorkflowContext withCandidate(AiWorkflowPlan plan,
                                              AgentOutput result,
                                              int currentIteration) {
        return new AgentWorkflowContext(execution, request, inputs, plan, assignmentId,
                assignment, result, feedback, currentIteration, maximumIterations, location,
                runControl);
    }

    public AgentWorkflowContext forIteration(AiWorkflowPlan plan,
                                             AgentOutput priorCandidate,
                                             int currentIteration) {
        return new AgentWorkflowContext(execution, request, List.of(), plan, null, null,
                priorCandidate, feedback, currentIteration, maximumIterations, location,
                runControl);
    }

    public AgentWorkflowContext withFeedback(AiWorkflowFeedback value) {
        if (value == null) return this;
        var updated = new java.util.ArrayList<>(feedback);
        updated.add(value);
        return new AgentWorkflowContext(execution, request, inputs, workflow, assignmentId,
                assignment, candidate, updated, iteration, maximumIterations, location,
                runControl);
    }

    public AgentWorkflowContext withExecution(AgentExecutionContext value) {
        return new AgentWorkflowContext(Objects.requireNonNull(value, "execution"), request,
                inputs, workflow, assignmentId, assignment, candidate, feedback, iteration,
                maximumIterations, location, runControl);
    }

    /** Installs the request-global Workflow budget without changing the definition input. */
    public AgentWorkflowContext withRunControl(WorkflowRunControl value) {
        return new AgentWorkflowContext(execution, request, inputs, workflow, assignmentId,
                assignment, candidate, feedback, iteration, maximumIterations, location,
                Objects.requireNonNull(value, "runControl"));
    }

    /** Builds the trusted scope shared by every definition-owned model turn and policy check. */
    public ExecutionScope executionScope(ExecutionScope.Purpose purpose) {
        return new ExecutionScope(request.requestId(), request.conversationId(),
                request.requesterId(), execution.agentDepth(),
                Objects.requireNonNull(purpose, "purpose"), execution.guardrailDecisionIds());
    }

    public void checkpoint() {
        runControl.checkpoint();
    }

    public void recordUsage(AiUsageSnapshot usage) {
        if (usage != null) runControl.recordUsage(usage);
    }

    public void recordUsage(String agentName, AgentRunResult result) {
        if (result == null) return;
        result.usage().ifPresent(usage -> runControl.recordAttemptUsage(new AiUsageSnapshot(
                location != null ? location.nodeId() : null,
                agentName, usage.inputTokens(), usage.outputTokens(), usage.modelCalls())));
    }

    public void registerUsage(Supplier<AiUsageSnapshot> usage,
                              Runnable lateWriteFence) {
        runControl.registerUsage(Objects.requireNonNull(usage, "usage"),
                Objects.requireNonNull(lateWriteFence, "lateWriteFence"));
    }

    public void registerAttemptUsage(Supplier<AiUsageSnapshot> usage,
                                     Runnable lateWriteFence) {
        runControl.registerAttemptUsage(Objects.requireNonNull(usage, "usage"),
                Objects.requireNonNull(lateWriteFence, "lateWriteFence"));
    }

    public Map<String, Object> observationContext() {
        if (location == null) return Map.of();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflow", location.workflowId());
        metadata.put("node_id", location.nodeId());
        if (location.parentNodeId() != null) {
            metadata.put("parent_node_id", location.parentNodeId());
        }
        metadata.put("depth", location.depth());
        return Map.copyOf(metadata);
    }

    /** Server-authored execution identity used to nest Agent spans under Workflow spans. */
    public record Location(String workflowId, String nodeId,
                           String parentNodeId, int depth) {
        public Location {
            workflowId = required(workflowId, "workflowId");
            nodeId = required(nodeId, "nodeId");
            if (parentNodeId != null) parentNodeId = required(parentNodeId, "parentNodeId");
            if (depth < 0) throw new IllegalArgumentException("depth must not be negative");
        }
    }

    /** Protocol-neutral request facts required by routing and planning Agents. */
    public record Request(String requestId, String conversationId, String requesterId,
                          String modelName, String prompt,
                          boolean hasAttachments, boolean hasPageContext,
                          int maximumAgents, String strategy,
                          String workflowPreference, boolean delegationRequested,
                          boolean mutationConfirmation) {
        public Request {
            requestId = required(requestId, "requestId");
            conversationId = required(conversationId, "conversationId");
            requesterId = required(requesterId, "requesterId");
            modelName = required(modelName, "modelName");
            prompt = Objects.requireNonNullElse(prompt, "");
            if (maximumAgents < 1) throw new IllegalArgumentException("maximumAgents must be positive");
            strategy = Objects.requireNonNullElse(strategy, "balanced");
        }

    }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }
}

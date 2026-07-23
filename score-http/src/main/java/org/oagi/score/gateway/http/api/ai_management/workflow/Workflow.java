package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInstructions;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.execution.WorkflowRequestAdapter;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Recursive Agent call-flow executor.
 *
 * <p>The main queue starts with the Gateway Agent. An Agent may complete its
 * unit, hand off to another Agent, or enqueue a child Workflow. A child
 * Workflow follows the same rules with its own queue and returns one result to
 * its parent. No execution-pattern type or compiler registry is involved.</p>
 */
@Component
public final class Workflow {

    private static final int DEFAULT_MAXIMUM_ITERATIONS = 3;
    private static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(2);
    private static final String PARTIAL_FAILURE_NOTICE =
            "Some requested steps could not be completed. Successful actions may already "
                    + "have taken effect; review the result before retrying incomplete steps.";

    private final AiChatExecutor chatExecutor;
    private final AiRequestRegistry requests;
    private final WorkflowAgentRegistry agents;
    private final WorkflowAgent gateway;
    private final WorkflowAgent assistant;
    private final WorkflowAgent evaluator;
    private final WorkflowAgent synthesizer;
    private final int maximumIterations;
    private final Duration executionTimeout;
    private final WorkflowPlanValidator validator = new WorkflowPlanValidator();

    @Autowired
    public Workflow(AiChatExecutor chatExecutor, AiAgentCatalog catalog,
                    AgentInstructions instructions, AiRequestRegistry requests,
                    ScoreAiProperties properties,
                    ObjectProvider<WorkflowAgent> installedAgents) {
        this(chatExecutor, catalog, instructions, requests,
                properties != null
                        ? properties.getMultiAgent().getMaximumWorkflowIterations()
                        : DEFAULT_MAXIMUM_ITERATIONS,
                properties != null
                        ? properties.getMultiAgent().getSpecialistTimeout()
                        : DEFAULT_EXECUTION_TIMEOUT,
                installedAgents != null
                        ? installedAgents.orderedStream().toList() : List.of());
    }

    Workflow(AiChatExecutor chatExecutor, AiAgentCatalog catalog,
             AgentInstructions instructions, AiRequestRegistry requests,
             int maximumIterations, List<? extends WorkflowAgent> installedAgents) {
        this(chatExecutor, catalog, instructions, requests, maximumIterations,
                DEFAULT_EXECUTION_TIMEOUT, installedAgents);
    }

    Workflow(AiChatExecutor chatExecutor, AiAgentCatalog catalog,
             AgentInstructions instructions, AiRequestRegistry requests,
             int maximumIterations, Duration executionTimeout,
             List<? extends WorkflowAgent> installedAgents) {
        this.chatExecutor = chatExecutor;
        this.requests = requests;
        if (maximumIterations < 1) {
            throw new IllegalArgumentException("Maximum Workflow iterations must be positive.");
        }
        this.maximumIterations = maximumIterations;
        if (executionTimeout == null || executionTimeout.isZero()
                || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("Workflow execution timeout must be positive.");
        }
        this.executionTimeout = executionTimeout;
        this.agents = new WorkflowAgentRegistry(
                chatExecutor, catalog, instructions, installedAgents);
        this.gateway = role("gateway-agent");
        this.assistant = role("connectcenter-assistant");
        this.evaluator = role("workflow-evaluator");
        this.synthesizer = role("workflow-synthesizer");
    }

    /** Compatibility constructor for focused service tests without a Spring graph. */
    public Workflow(AiChatExecutor chatExecutor) {
        this(chatExecutor, null, null, null, DEFAULT_MAXIMUM_ITERATIONS,
                DEFAULT_EXECUTION_TIMEOUT, List.of());
    }

    public AiChatExecutor.Result execute(AiChatExecutor.Context context) {
        Objects.requireNonNull(context, "context");
        if (chatExecutor == null) {
            throw new IllegalStateException("No AI chat executor is configured.");
        }
        if (context.agentDepth() != 0) {
            throw new IllegalArgumentException("A main Workflow must start at Agent depth zero.");
        }
        if (agents.isEmpty()) return chatExecutor.execute(context);

        Map<String, Object> namespace = namespace(context, "main", "main", null, 0, 1);
        AiTrajectoryRecorder workflowRecorder = context.recorder().fork(namespace);
        WorkflowRunBudget budget = new WorkflowRunBudget(context.request().requestId(),
                context.recorder(), executionTimeout,
                () -> cancellationFence(context.request().requestId()),
                () -> timeoutRequest(context.request().requestId()));
        AgentWorkflowContext rootContext = AgentWorkflowContext.root(
                context, WorkflowRequestAdapter.from(context), maximumIterations, budget)
                .inWorkflow(null,
                new AgentWorkflowContext.Location("main", "main", null, 0));
        RunState state = new RunState(rootContext);
        Deque<Call> queue = new ArrayDeque<>();
        WorkflowAgent first = gateway != null ? gateway : assistant;
        if (first == null) return chatExecutor.execute(context);
        queue.addLast(new AgentCall(first));

        workflowRecorder.lifecycle("workflow_started", "Starting the Agent call flow.",
                lifecycle(namespace, "started", Map.of("queued", queue.size())));
        try {
            while (!queue.isEmpty()) {
                budget.checkpoint();
                Call call = queue.removeFirst();
                if (call instanceof AgentCall agentCall) {
                    handleAgentDecision(budget.invoke(agentCall.agent(), state.context),
                            state, queue);
                } else if (call instanceof ChildWorkflowCall child) {
                    WorkflowResult result;
                    try {
                        result = executeChild(state.context, child.workflow().root(),
                                "main", 1, child.workflow(),
                                state.iteration + ":" + child.workflow().root().id(), budget);
                    } catch (CancellationException | AgentInputRefusedException terminal) {
                        throw terminal;
                    } catch (RuntimeException failedIteration) {
                        if (state.candidate == null) throw failedIteration;
                        Map<String, Object> retained = new LinkedHashMap<>(
                                state.candidate.traceMetadata());
                        retained.put("evaluation_status", "iteration_failed");
                        retained.put("failed_iteration", state.iteration);
                        retained.put("iteration_failure_type",
                                failedIteration.getClass().getSimpleName());
                        state.candidate = new AiChatExecutor.Result(
                                state.candidate.answer(), Map.copyOf(retained));
                        workflowRecorder.lifecycle("workflow_iteration_failed",
                                "A later Workflow iteration failed; retaining the last successful result.",
                                lifecycle(namespace, "iteration_failed", Map.of(
                                        "iteration", state.iteration,
                                        "reason", failedIteration.getClass().getSimpleName())));
                        queue.clear();
                        continue;
                    }
                    state.plan = child.workflow();
                    state.candidate = resultAsCandidate(result, child.workflow(), state.iteration);
                    state.context = state.context.withCandidate(
                            child.workflow(), state.candidate, state.iteration);
                    if (evaluator != null) queue.addLast(new AgentCall(evaluator));
                }
            }
            if (state.candidate == null) {
                throw new IllegalStateException("The Workflow queue completed without a response.");
            }
            Map<String, Object> metadata = new LinkedHashMap<>(state.candidate.traceMetadata());
            metadata.put("workflow", "main");
            metadata.put("workflow_iterations", state.iteration);
            metadata.put("workflow_queue_calls", budget.usedCalls());
            AiChatExecutor.Result result = new AiChatExecutor.Result(
                    state.candidate.answer(), Map.copyOf(metadata));
            budget.settleUsage();
            workflowRecorder.terminalLifecycle("workflow_completed", "Agent call flow completed.",
                    lifecycle(namespace, "completed", Map.of(
                            "queue_calls", budget.usedCalls(),
                            "iterations", state.iteration)));
            return result;
        } catch (CancellationException failure) {
            budget.settleUsage();
            workflowRecorder.terminalLifecycle("workflow_cancelled", "Agent call flow stopped.",
                    lifecycle(namespace, "cancelled", Map.of()));
            throw failure;
        } catch (AgentInputRefusedException refusal) {
            budget.settleUsage();
            workflowRecorder.terminalLifecycle("workflow_refused", "Agent call flow refused.",
                    lifecycle(namespace, "refused", Map.of()));
            throw refusal;
        } catch (RuntimeException failure) {
            budget.settleUsage();
            workflowRecorder.terminalLifecycle("workflow_failed", "Agent call flow failed.",
                    lifecycle(namespace, "failed", Map.of(
                            "reason", failure.getClass().getSimpleName())));
            throw failure;
        } finally {
            budget.settleUsage();
        }
    }

    private void handleAgentDecision(AgentDecision decision, RunState state,
                                     Deque<Call> queue) {
        if (decision instanceof AgentDecision.Complete complete) {
            state.candidate = complete.result();
            state.context = state.context.withCandidate(
                    state.plan, state.candidate, state.iteration);
            return;
        }
        if (decision instanceof AgentDecision.Handoff handoff) {
            if (handoff.feedback() != null) {
                state.context = state.context.withFeedback(handoff.feedback());
            }
            queue.addLast(new AgentCall(resolve(handoff.target())));
            return;
        }
        AgentDecision.Delegate delegate = (AgentDecision.Delegate) decision;
        validate(delegate.workflow(), state.context.request().maximumAgents());
        state.iteration++;
        if (state.iteration > maximumIterations) {
            if (state.candidate != null) return;
            throw new IllegalStateException("The Workflow exceeded its iteration limit.");
        }
        state.plan = delegate.workflow();
        state.context = state.context.forIteration(
                state.plan, state.candidate, state.iteration);
        queue.addLast(new ChildWorkflowCall(delegate.workflow()));
    }

    private WorkflowResult executeChild(AgentWorkflowContext parent,
                                        AiWorkflowPlan.WorkflowDefinition workflow,
                                        String parentNodeId, int depth,
                                        AiWorkflowPlan plan, String nodeKey,
                                        WorkflowRunBudget budget) {
        budget.charge("child Workflow");
        if (depth > WorkflowPlanValidator.MAXIMUM_DEPTH) {
            throw new IllegalArgumentException("Workflow nesting is too deep.");
        }
        AiChatExecutor.Context execution = parent.execution();
        String nodeId = runtimeNodeId(parentNodeId, nodeKey);
        Map<String, Object> namespace = namespace(execution, workflow.id(), nodeId, parentNodeId,
                depth, workflow.members().size());
        AiTrajectoryRecorder recorder = execution.recorder().fork(namespace);
        AgentWorkflowContext local = parent.inWorkflow(plan,
                new AgentWorkflowContext.Location(workflow.id(), nodeId, parentNodeId, depth));
        recorder.lifecycle("workflow_started", plan.guideMessage(),
                lifecycle(namespace, "started", Map.of(
                        "member_count", workflow.members().size())));

        try {
            Deque<AiWorkflowPlan.Member> queue = new ArrayDeque<>(workflow.members());
            List<WorkflowResult> results = new ArrayList<>();
            while (!queue.isEmpty()) {
                cancellationFence(execution.request().requestId());
                AiWorkflowPlan.Member member = queue.removeFirst();
                List<WorkflowResult> upstream = successfulInputs(local.inputs(), results);
                if (member.agent() != null) {
                        results.add(executeAssignedAgent(local, plan, member, upstream, budget));
                } else {
                    try {
                        AiWorkflowPlan nestedPlan = new AiWorkflowPlan(member.workflow(),
                                plan.guideMessage(), plan.synthesisGuideMessage());
                        results.add(executeChild(local.withInputs(upstream), member.workflow(),
                                nodeId, depth + 1, nestedPlan, member.id(), budget));
                    } catch (CancellationException | AgentInputRefusedException failure) {
                        throw failure;
                    } catch (RuntimeException failure) {
                        results.add(WorkflowResult.failure(member.id(), failure));
                    }
                }
            }
            List<WorkflowResult> completed = results.stream()
                    .filter(WorkflowResult::successful).toList();
            if (completed.isEmpty()) {
                throw new IllegalStateException(
                        "Every member of Workflow " + workflow.id() + " failed.");
            }

            budget.checkpoint();
            AiChatExecutor.Result output = synthesize(local, plan, results, budget);
            int directFailed = results.size() - completed.size();
            int failed = failureCount(results);
            Map<String, Object> metadata = new LinkedHashMap<>(output.traceMetadata());
            metadata.put("workflow", workflow.id());
            metadata.put("node_id", nodeId);
            metadata.put("completed", completed.size());
            metadata.put("direct_failed", directFailed);
            metadata.put("failed", failed);
            String answer = output.answer();
            if (failed > 0) {
                metadata.put("partial_failure", true);
                metadata.put("partial_failure_count", failed);
                metadata.put("partial_failure_notice", true);
                answer = partialFailureAnswer(answer);
            }
            WorkflowResult result = WorkflowResult.success(workflow.id(), answer,
                    Map.copyOf(metadata), results);
            recorder.terminalLifecycle("workflow_completed", plan.synthesisGuideMessage(),
                    lifecycle(namespace, "completed", Map.of(
                            "completed", completed.size(),
                            "failed", directFailed,
                            "failure_count", failed,
                            "partial_failure", failed > 0)));
            return result;
        } catch (CancellationException failure) {
            recorder.terminalLifecycle("workflow_cancelled", "Workflow execution stopped.",
                    lifecycle(namespace, "cancelled", Map.of()));
            throw failure;
        } catch (AgentInputRefusedException refusal) {
            recorder.terminalLifecycle("workflow_refused", "Workflow execution refused.",
                    lifecycle(namespace, "refused", Map.of()));
            throw refusal;
        } catch (RuntimeException failure) {
            recorder.terminalLifecycle("workflow_failed", "Workflow execution failed.",
                    lifecycle(namespace, "failed", Map.of(
                            "reason", failure.getClass().getSimpleName())));
            throw failure;
        }
    }

    private WorkflowResult executeAssignedAgent(AgentWorkflowContext parent,
                                                AiWorkflowPlan plan,
                                                AiWorkflowPlan.Member member,
                                                List<WorkflowResult> upstream,
                                                WorkflowRunBudget budget) {
        try {
            WorkflowAgent agent = assigned(member.agent());
            AiChatExecutor.ToolPolicy policy = assignmentToolPolicy(
                    parent.execution(), member.agent().toolAccess());
            AgentWorkflowContext context = parent.withExecution(
                            parent.execution().forWorkflowAssignment(
                                    policy, agent.id().value()))
                    .withAssignment(
                    plan, member.id(), member.agent(), upstream);
            AgentDecision decision = budget.invoke(agent, context);
            return decisionResult(decision, context, member.id(), budget);
        } catch (CancellationException | AgentInputRefusedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return WorkflowResult.failure(member.id(), failure);
        }
    }

    private WorkflowResult decisionResult(AgentDecision decision,
                                          AgentWorkflowContext context,
                                          String memberId, WorkflowRunBudget budget) {
        if (decision instanceof AgentDecision.Complete complete) {
            return WorkflowResult.success(memberId, complete.result().answer(),
                    complete.result().traceMetadata(), List.of());
        }
        if (decision instanceof AgentDecision.Delegate delegate) {
            validate(delegate.workflow(), context.request().maximumAgents());
            AgentWorkflowContext.Location location = Objects.requireNonNull(
                    context.location(), "Workflow location");
            return executeChild(context, delegate.workflow().root(), location.nodeId(),
                    location.depth() + 1, delegate.workflow(), memberId + "-delegated",
                    budget);
        }
        AgentDecision.Handoff handoff = (AgentDecision.Handoff) decision;
        AgentWorkflowContext next = handoff.feedback() != null
                ? context.withFeedback(handoff.feedback()) : context;
        return decisionResult(budget.invoke(resolve(handoff.target()), next), next,
                memberId, budget);
    }

    private AiChatExecutor.Result synthesize(AgentWorkflowContext parent,
                                             AiWorkflowPlan plan,
                                             List<WorkflowResult> results,
                                             WorkflowRunBudget budget) {
        if (results.size() == 1 && results.getFirst().successful()) {
            WorkflowResult only = results.getFirst();
            return new AiChatExecutor.Result(only.output(), only.metadata());
        }
        if (synthesizer == null) {
            WorkflowResult last = results.reversed().stream()
                    .filter(WorkflowResult::successful).findFirst().orElseThrow();
            return new AiChatExecutor.Result(last.output(), last.metadata());
        }
        AgentWorkflowContext synthesis = parent.inWorkflow(plan,
                Objects.requireNonNull(parent.location(), "Workflow location"))
                .withInputs(results);
        AgentDecision decision = budget.invoke(synthesizer, synthesis);
        if (decision instanceof AgentDecision.Complete complete) return complete.result();
        throw new IllegalStateException("The Synthesizer Agent must complete its assigned unit.");
    }

    private AiChatExecutor.Result resultAsCandidate(WorkflowResult result,
                                                    AiWorkflowPlan plan,
                                                    int iteration) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("workflow", plan.root().id());
        metadata.put("workflow_iteration", iteration);
        return new AiChatExecutor.Result(result.output(), Map.copyOf(metadata));
    }

    private List<WorkflowResult> successfulInputs(List<WorkflowResult> inherited,
                                                  List<WorkflowResult> current) {
        List<WorkflowResult> inputs = new ArrayList<>(inherited.size() + current.size());
        inherited.stream().filter(WorkflowResult::successful).forEach(inputs::add);
        current.stream().filter(WorkflowResult::successful).forEach(inputs::add);
        return List.copyOf(inputs);
    }

    private int failureCount(List<WorkflowResult> results) {
        int failures = 0;
        for (WorkflowResult result : results) {
            if (!result.successful()) failures++;
            failures += failureCount(result.children());
        }
        return failures;
    }

    private String partialFailureAnswer(String answer) {
        String value = Objects.requireNonNullElse(answer, "").stripTrailing();
        if (value.contains(PARTIAL_FAILURE_NOTICE)) return value;
        return value + (value.isEmpty() ? "" : "\n\n") + PARTIAL_FAILURE_NOTICE;
    }

    private String runtimeNodeId(String parentNodeId, String nodeKey) {
        String parent = StringUtils.hasText(parentNodeId) ? parentNodeId.strip() : "workflow";
        String key = StringUtils.hasText(nodeKey) ? nodeKey.strip() : "node";
        String candidate = parent + ":" + key;
        if (candidate.length() <= 240) return candidate;
        return "workflow:" + UUID.nameUUIDFromBytes(
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private void validate(AiWorkflowPlan plan, int maximumAgents) {
        validator.validate(plan, maximumAgents, agents::assignable);
    }

    private WorkflowAgent assigned(AiWorkflowPlan.AgentTask task) {
        return agents.assigned(task);
    }

    private AiChatExecutor.ToolPolicy assignmentToolPolicy(
            AiChatExecutor.Context parent, AiWorkflowPlan.ToolAccess requested) {
        if (!parent.toolsEnabled() || parent.toolPolicy() == AiChatExecutor.ToolPolicy.NONE
                || requested == AiWorkflowPlan.ToolAccess.NONE) {
            return AiChatExecutor.ToolPolicy.NONE;
        }
        if (requested == AiWorkflowPlan.ToolAccess.FULL
                && parent.toolPolicy() == AiChatExecutor.ToolPolicy.FULL) {
            return AiChatExecutor.ToolPolicy.FULL;
        }
        return AiChatExecutor.ToolPolicy.READ_ONLY;
    }

    private WorkflowAgent resolve(Agent.AgentId target) {
        return agents.resolve(target);
    }

    private WorkflowAgent role(String id) {
        return agents.role(id);
    }

    private Map<String, Object> namespace(AiChatExecutor.Context context,
                                          String workflowName, String nodeId,
                                          String parentNodeId, int depth, int members) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workflow", workflowName);
        value.put("node_id", nodeId);
        if (parentNodeId != null) value.put("parent_node_id", parentNodeId);
        value.put("depth", depth);
        value.put("member_count", members);
        value.put("request_id", context.request().requestId());
        return Map.copyOf(value);
    }

    private Map<String, Object> lifecycle(Map<String, Object> namespace,
                                          String status, Map<String, Object> additional) {
        Map<String, Object> value = new LinkedHashMap<>(namespace);
        value.put("status", status);
        value.putAll(additional);
        return Map.copyOf(value);
    }

    private void cancellationFence(String requestId) {
        if (Thread.currentThread().isInterrupted()
                || requests != null && requests.shouldDiscardResult(requestId)) {
            throw new CancellationException("Workflow execution was interrupted.");
        }
    }

    private void timeoutRequest(String requestId) {
        if (requests != null) requests.timeoutExecution(requestId);
    }

    private sealed interface Call permits AgentCall, ChildWorkflowCall { }

    private record AgentCall(WorkflowAgent agent) implements Call { }

    private record ChildWorkflowCall(AiWorkflowPlan workflow) implements Call { }

    private static final class RunState {
        private AgentWorkflowContext context;
        private AiWorkflowPlan plan;
        private AiChatExecutor.Result candidate;
        private int iteration;

        private RunState(AgentWorkflowContext context) {
            this.context = context;
        }
    }

}

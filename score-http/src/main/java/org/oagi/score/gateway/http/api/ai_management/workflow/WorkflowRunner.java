package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutputRetryHandoffException;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.WorkflowPlanValidator;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
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
 * <p>The main queue starts with the Gateway Agent. The shared {@link AgentRunner}
 * executes the addressed definition, and the resulting decision may complete
 * its unit, hand off to another Agent, or enqueue a child Workflow. A child
 * Workflow follows the same rules with its own queue and returns one result to
 * its parent. No execution-pattern type or compiler registry is involved.</p>
 */
@Component
public final class WorkflowRunner {

    private static final int DEFAULT_MAXIMUM_ITERATIONS = 3;
    private static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(2);
    private static final String PARTIAL_FAILURE_NOTICE =
            "Some requested steps could not be completed. Successful actions may already "
                    + "have taken effect; review the result before retrying incomplete steps.";

    private final AiRequestRegistry requests;
    private final AgentRunner agents;
    private final Agent gateway;
    private final Agent assistant;
    private final Agent evaluator;
    private final Agent synthesizer;
    private final int maximumIterations;
    private final Duration executionTimeout;
    private final WorkflowPlanValidator validator = new WorkflowPlanValidator();
    private final WorkflowGraphScheduler graphScheduler = new WorkflowGraphScheduler();

    @Autowired
    public WorkflowRunner(AgentRunner agents, AiRequestRegistry requests,
                    ScoreAiProperties properties) {
        this(agents, requests,
                properties != null
                        ? properties.getMultiAgent().getMaximumWorkflowIterations()
                        : DEFAULT_MAXIMUM_ITERATIONS,
                properties != null
                        ? properties.getMultiAgent().getSpecialistTimeout()
                        : DEFAULT_EXECUTION_TIMEOUT);
    }

    public WorkflowRunner(AgentRunner agents,
             AiRequestRegistry requests, int maximumIterations,
             Duration executionTimeout) {
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
        this.agents = Objects.requireNonNull(agents, "agents");
        this.gateway = role("gateway-agent");
        this.assistant = role("connectcenter-assistant");
        this.evaluator = role("workflow-evaluator");
        this.synthesizer = role("workflow-synthesizer");
    }

    public WorkflowRunner(AgentRunner agents, AiRequestRegistry requests,
                           int maximumIterations) {
        this(agents, requests, maximumIterations, DEFAULT_EXECUTION_TIMEOUT);
    }

    /** Schedules the already-adapted root Workflow context. */
    public AgentOutput execute(AgentWorkflowContext root) {
        Objects.requireNonNull(root, "root");
        AgentExecutionContext context = root.execution();
        if (context.agentDepth() != 0) {
            throw new IllegalArgumentException("A main Workflow must start at Agent depth zero.");
        }
        if (agents.isEmpty()) {
            throw new IllegalStateException("No Agent definitions are available for this Workflow.");
        }

        Map<String, Object> namespace = namespace(context, "main", "main", null, 0, 1);
        AgentExecutionRecorder workflowRecorder = context.recorder().fork(namespace);
        WorkflowRunBudget budget = new WorkflowRunBudget(context.requestId(),
                context.recorder(), executionTimeout,
                () -> cancellationFence(context.requestId()),
                () -> timeoutRequest(context.requestId()));
        AgentWorkflowContext rootContext = root.withRunControl(budget)
                .inWorkflow(null,
                new AgentWorkflowContext.Location("main", "main", null, 0));
        RunState state = new RunState(rootContext);
        Deque<Call> queue = new ArrayDeque<>();
        Agent first = gateway != null ? gateway : assistant;
        if (first == null) {
            throw new IllegalStateException("The Workflow has no starting Agent.");
        }
        queue.addLast(new AgentCall(first.callId()));

        workflowRecorder.lifecycle("workflow_started", "Starting the Agent call flow.",
                lifecycle(namespace, "started", Map.of("queued", queue.size())));
        try {
            while (!queue.isEmpty()) {
                budget.checkpoint();
                Call call = queue.removeFirst();
                if (call instanceof AgentCall agentCall) {
                    handleAgentDecision(budget.invoke(agents, agentCall.id(), state.context),
                            state, queue);
                } else if (call instanceof ChildWorkflowCall child) {
                    WorkflowResult result;
                    try {
                        result = executeChild(state.context, child.workflow().root(),
                                "main", 1, child.workflow(),
                                state.iteration + ":" + child.workflow().root().id(), budget);
                    } catch (CancellationException | AgentGuardrailRefusedException
                             | AgentOutputRetryHandoffException
                             | WorkflowRunBudget.DeadlineExceededException terminal) {
                        throw terminal;
                    } catch (RuntimeException failedIteration) {
                        if (state.candidate == null) throw failedIteration;
                        Map<String, Object> retained = new LinkedHashMap<>(
                                state.candidate.metadata());
                        retained.put("evaluation_status", "iteration_failed");
                        retained.put("failed_iteration", state.iteration);
                        retained.put("iteration_failure_type",
                                failedIteration.getClass().getSimpleName());
                        state.candidate = state.candidate.withMetadata(Map.copyOf(retained));
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
                    if (evaluator != null) queue.addLast(new AgentCall(evaluator.callId()));
                }
            }
            if (state.candidate == null) {
                throw new IllegalStateException("The Workflow queue completed without a response.");
            }
            Map<String, Object> metadata = new LinkedHashMap<>(state.candidate.metadata());
            metadata.put("workflow", "main");
            metadata.put("workflow_iterations", state.iteration);
            metadata.put("workflow_queue_calls", budget.usedCalls());
            AgentOutput result = state.candidate.withMetadata(Map.copyOf(metadata));
            budget.settleUsage();
            workflowRecorder.terminalLifecycle("workflow_completed", "Agent call flow completed.",
                    lifecycle(namespace, "completed", Map.of(
                            "queue_calls", budget.usedCalls(),
                            "iterations", state.iteration)));
            return result;
        } catch (AgentOutputRetryHandoffException handoff) {
            budget.settleUsage();
            workflowRecorder.terminalLifecycle("workflow_output_retry_handoff",
                    "Regenerating the response without tools.",
                    lifecycle(namespace, "retry_handoff", Map.of(
                            "agent_id", handoff.agentId().value())));
            throw handoff;
        } catch (WorkflowRunBudget.DeadlineExceededException timeout) {
            budget.settleUsage();
            context.recorder().sealAgainstLateCallbacks();
            workflowRecorder.terminalLifecycle("workflow_timed_out",
                    "Agent call flow exceeded its execution deadline.",
                    lifecycle(namespace, "timed_out", Map.of()));
            throw timeout;
        } catch (CancellationException failure) {
            budget.settleUsage();
            context.recorder().sealAgainstLateCallbacks();
            workflowRecorder.terminalLifecycle("workflow_cancelled", "Agent call flow stopped.",
                    lifecycle(namespace, "cancelled", Map.of()));
            throw failure;
        } catch (AgentGuardrailRefusedException refusal) {
            budget.settleUsage();
            workflowRecorder.terminalLifecycle("workflow_refused", "Agent call flow refused.",
                    lifecycle(namespace, "refused", Map.of()));
            throw refusal;
        } catch (RuntimeException failure) {
            budget.settleUsage();
            context.recorder().sealAgainstLateCallbacks();
            workflowRecorder.terminalLifecycle("workflow_failed", "Agent call flow failed.",
                    lifecycle(namespace, "failed", Map.of(
                            "reason", failure.getClass().getSimpleName())));
            throw failure;
        } finally {
            budget.settleUsage();
        }
    }

    /** The configured maximum number of Planner/Evaluator iterations for root adapters. */
    public int maximumIterations() {
        return maximumIterations;
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
            queue.addLast(new AgentCall(resolve(handoff.target()).callId()));
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
        AgentExecutionContext execution = parent.execution();
        String nodeId = runtimeNodeId(parentNodeId, nodeKey);
        Map<String, Object> namespace = namespace(execution, workflow.id(), nodeId, parentNodeId,
                depth, workflow.members().size());
        AgentExecutionRecorder recorder = execution.recorder().fork(namespace);
        AgentWorkflowContext local = parent.inWorkflow(plan,
                new AgentWorkflowContext.Location(workflow.id(), nodeId, parentNodeId, depth));
        recorder.lifecycle("workflow_started", plan.guideMessage(),
                lifecycle(namespace, "started", Map.of(
                        "member_count", workflow.members().size())));

        try {
            List<WorkflowResult> results = graphScheduler.execute(
                    workflow, local.inputs(), budget::checkpoint,
                    (member, upstream) -> executeMember(local, plan, member, upstream,
                            nodeId, depth, budget));
            List<WorkflowResult> completed = results.stream()
                    .filter(WorkflowResult::successful).toList();
            if (completed.isEmpty()) {
                throw new IllegalStateException(
                        "Every member of Workflow " + workflow.id() + " failed.");
            }

            budget.checkpoint();
            AgentOutput output = synthesize(local, plan, results, budget);
            int directFailed = results.size() - completed.size();
            int failed = failureCount(results);
            Map<String, Object> metadata = new LinkedHashMap<>(output.metadata());
            metadata.put("workflow", workflow.id());
            metadata.put("node_id", nodeId);
            metadata.put("completed", completed.size());
            metadata.put("direct_failed", directFailed);
            metadata.put("failed", failed);
            String answer = output.content();
            if (failed > 0) {
                metadata.put("partial_failure", true);
                metadata.put("partial_failure_count", failed);
                metadata.put("partial_failure_notice", true);
                answer = partialFailureAnswer(answer);
            }
            AgentOutput completedOutput = failed > 0
                    ? new AgentOutput(answer, Map.copyOf(metadata))
                    : output.withMetadata(Map.copyOf(metadata));
            WorkflowResult result = WorkflowResult.success(
                    workflow.id(), completedOutput, results);
            recorder.terminalLifecycle("workflow_completed", plan.synthesisGuideMessage(),
                    lifecycle(namespace, "completed", Map.of(
                            "completed", completed.size(),
                            "failed", directFailed,
                            "failure_count", failed,
                            "partial_failure", failed > 0)));
            return result;
        } catch (AgentOutputRetryHandoffException handoff) {
            recorder.terminalLifecycle("workflow_output_retry_handoff",
                    "Regenerating the response without tools.",
                    lifecycle(namespace, "retry_handoff", Map.of(
                            "agent_id", handoff.agentId().value())));
            throw handoff;
        } catch (WorkflowRunBudget.DeadlineExceededException timeout) {
            recorder.terminalLifecycle("workflow_timed_out",
                    "Workflow execution exceeded its deadline.",
                    lifecycle(namespace, "timed_out", Map.of()));
            throw timeout;
        } catch (CancellationException failure) {
            recorder.terminalLifecycle("workflow_cancelled", "Workflow execution stopped.",
                    lifecycle(namespace, "cancelled", Map.of()));
            throw failure;
        } catch (AgentGuardrailRefusedException refusal) {
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

    private WorkflowResult executeMember(AgentWorkflowContext local,
                                         AiWorkflowPlan plan,
                                         AiWorkflowPlan.Member member,
                                         List<WorkflowResult> upstream,
                                         String nodeId, int depth,
                                         WorkflowRunBudget budget) {
        cancellationFence(local.execution().requestId());
        if (member.agent() != null) {
            return executeAssignedAgent(local, plan, member, upstream, budget);
        }
        try {
            AiWorkflowPlan nestedPlan = new AiWorkflowPlan(member.workflow(),
                    plan.guideMessage(), plan.synthesisGuideMessage());
            return executeChild(local.withInputs(upstream), member.workflow(),
                    nodeId, depth + 1, nestedPlan, member.id(), budget);
        } catch (CancellationException | AgentGuardrailRefusedException
                 | AgentOutputRetryHandoffException
                 | WorkflowRunBudget.DeadlineExceededException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return WorkflowResult.failure(member.id(), failure);
        }
    }

    private WorkflowResult executeAssignedAgent(AgentWorkflowContext parent,
                                                AiWorkflowPlan plan,
                                                AiWorkflowPlan.Member member,
                                                List<WorkflowResult> upstream,
                                                WorkflowRunBudget budget) {
        try {
            Agent agent = assigned(member.agent());
            AgentToolPolicy policy = assignmentToolPolicy(
                    parent.execution(), member.agent().toolAccess());
            AgentWorkflowContext context = parent.withExecution(
                            parent.execution().forWorkflowAssignment(
                                    policy, agent.id().value()))
                    .withAssignment(
                    plan, member.id(), member.agent(), upstream);
            AgentDecision decision = budget.invoke(agents, agent.callId(), context);
            return decisionResult(decision, context, member.id(), budget);
        } catch (CancellationException | AgentGuardrailRefusedException
                 | AgentOutputRetryHandoffException
                 | WorkflowRunBudget.DeadlineExceededException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return WorkflowResult.failure(member.id(), failure);
        }
    }

    private WorkflowResult decisionResult(AgentDecision decision,
                                          AgentWorkflowContext context,
                                          String memberId, WorkflowRunBudget budget) {
        if (decision instanceof AgentDecision.Complete complete) {
            return WorkflowResult.success(memberId, complete.result(), List.of());
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
        return decisionResult(budget.invoke(agents, resolve(handoff.target()).callId(), next), next,
                memberId, budget);
    }

    private AgentOutput synthesize(AgentWorkflowContext parent,
                                             AiWorkflowPlan plan,
                                             List<WorkflowResult> results,
                                             WorkflowRunBudget budget) {
        if (results.size() == 1 && results.getFirst().successful()) {
            WorkflowResult only = results.getFirst();
            return only.result();
        }
        if (synthesizer == null) {
            WorkflowResult last = results.reversed().stream()
                    .filter(WorkflowResult::successful).findFirst().orElseThrow();
            return last.result();
        }
        AgentWorkflowContext synthesis = parent.inWorkflow(plan,
                Objects.requireNonNull(parent.location(), "Workflow location"))
                .withInputs(results);
        AgentDecision decision = budget.invoke(agents, synthesizer.callId(), synthesis);
        if (decision instanceof AgentDecision.Complete complete) return complete.result();
        throw new IllegalStateException("The Synthesizer Agent must complete its assigned unit.");
    }

    private AgentOutput resultAsCandidate(WorkflowResult result,
                                                    AiWorkflowPlan plan,
                                                    int iteration) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("workflow", plan.root().id());
        metadata.put("workflow_iteration", iteration);
        return result.result().withMetadata(Map.copyOf(metadata));
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

    private Agent assigned(AiWorkflowPlan.AgentTask task) {
        return agents.assigned(task);
    }

    private AgentToolPolicy assignmentToolPolicy(
            AgentExecutionContext parent, AiWorkflowPlan.ToolAccess requested) {
        return AgentToolPolicy.restrict(parent.toolPolicy(), parent.toolsEnabled(),
                requested == AiWorkflowPlan.ToolAccess.FULL,
                requested == AiWorkflowPlan.ToolAccess.NONE);
    }

    private Agent resolve(Agent.AgentId target) {
        return agents.resolve(target);
    }

    private Agent role(String id) {
        return agents.role(id);
    }

    private Map<String, Object> namespace(AgentExecutionContext context,
                                          String workflowName, String nodeId,
                                          String parentNodeId, int depth, int members) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workflow", workflowName);
        value.put("node_id", nodeId);
        if (parentNodeId != null) value.put("parent_node_id", parentNodeId);
        value.put("depth", depth);
        value.put("member_count", members);
        value.put("request_id", context.requestId());
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

    private record AgentCall(Agent.AgentId id) implements Call { }

    private record ChildWorkflowCall(AiWorkflowPlan workflow) implements Call { }

    private static final class RunState {
        private AgentWorkflowContext context;
        private AiWorkflowPlan plan;
        private AgentOutput candidate;
        private int iteration;

        private RunState(AgentWorkflowContext context) {
            this.context = context;
        }
    }

}

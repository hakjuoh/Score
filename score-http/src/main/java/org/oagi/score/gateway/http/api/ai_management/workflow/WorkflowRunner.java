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
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowType;
import org.oagi.score.gateway.http.api.ai_management.model.WorkflowPlanValidator;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import static org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowRuntimeMetadata.lifecycle;
import static org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowRuntimeMetadata.namespace;
import static org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowRuntimeMetadata.nodeId;

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
    private static final Duration DEFAULT_INACTIVITY_TIMEOUT = Duration.ofMinutes(2);
    private final AiRequestRegistry requests;
    private final AgentRunner agents;
    private final Agent gateway;
    private final Agent assistant;
    private final Agent evaluator;
    private final WorkflowResults results;
    private final int maximumIterations;
    private final Duration inactivityTimeout;
    private final WorkflowPlanPolicy planPolicy;
    private final WorkflowGraphScheduler graphScheduler = new WorkflowGraphScheduler();
    private final AiSpecialistAdmissionGate specialistAdmission;

    @Autowired
    public WorkflowRunner(AgentRunner agents, AiRequestRegistry requests,
                    ScoreAiProperties properties, ScoreAiObservability observability) {
        this(agents, requests,
                properties != null
                        ? properties.getMultiAgent().getMaximumWorkflowIterations()
                        : DEFAULT_MAXIMUM_ITERATIONS,
                properties != null
                        ? properties.getMultiAgent().getSpecialistInactivityTimeout()
                        : DEFAULT_INACTIVITY_TIMEOUT,
                properties != null
                        ? properties.getMultiAgent().getMaxConcurrentSpecialists() : 16,
                properties != null
                        ? properties.getMultiAgent().getMaxConcurrentSpecialistsPerUser() : 8,
                observability);
    }

    public WorkflowRunner(AgentRunner agents,
             AiRequestRegistry requests, int maximumIterations,
             Duration inactivityTimeout) {
        this(agents, requests, maximumIterations, inactivityTimeout, 16, 8);
    }

    WorkflowRunner(AgentRunner agents, AiRequestRegistry requests, int maximumIterations,
                   Duration inactivityTimeout, int globalSpecialists,
                   int specialistsPerUser) {
        this(agents, requests, maximumIterations, inactivityTimeout, globalSpecialists,
                specialistsPerUser, ScoreAiObservability.noop());
    }

    WorkflowRunner(AgentRunner agents, AiRequestRegistry requests, int maximumIterations,
                   Duration inactivityTimeout, int globalSpecialists,
                   int specialistsPerUser, ScoreAiObservability observability) {
        this.requests = requests;
        if (maximumIterations < 1) {
            throw new IllegalArgumentException("Maximum Workflow iterations must be positive.");
        }
        this.maximumIterations = maximumIterations;
        if (inactivityTimeout == null || inactivityTimeout.isZero()
                || inactivityTimeout.isNegative()) {
            throw new IllegalArgumentException("Agent inactivity timeout must be positive.");
        }
        this.inactivityTimeout = inactivityTimeout;
        this.specialistAdmission = new AiSpecialistAdmissionGate(
                globalSpecialists, specialistsPerUser, observability);
        this.agents = Objects.requireNonNull(agents, "agents");
        this.planPolicy = new WorkflowPlanPolicy(agents);
        this.gateway = agents.role("gateway-agent");
        this.assistant = agents.role("connectcenter-assistant");
        this.evaluator = agents.role("workflow-evaluator");
        this.results = new WorkflowResults(agents, agents.role("workflow-synthesizer"));
    }

    public WorkflowRunner(AgentRunner agents, AiRequestRegistry requests,
                           int maximumIterations) {
        this(agents, requests, maximumIterations, DEFAULT_INACTIVITY_TIMEOUT);
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

        Map<String, Object> namespace = namespace(
                context, "main", "main", null, 0, 1, AiWorkflowType.DIRECT);
        AgentExecutionRecorder workflowRecorder = context.recorder().fork(namespace);
        WorkflowRunBudget budget = new WorkflowRunBudget(context.requestId(),
                context.recorder(), inactivityTimeout,
                () -> cancellationFence(context.requestId()),
                () -> requestProgress(context.requestId()),
                root.request().maximumAgents());
        AgentWorkflowContext rootContext = root.withRunControl(budget)
                .inWorkflow(null,
                new AgentWorkflowContext.Location(
                        "main", "main", null, 0, AiWorkflowType.DIRECT));
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
                             | AgentInvocationStalledException terminal) {
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
                    state.candidate = results.asCandidate(
                            result, child.workflow(), state.iteration);
                    state.context = state.context.withCandidate(
                            child.workflow(), state.candidate, state.iteration);
                    if (shouldEvaluate(state.context)) {
                        queue.addLast(new AgentCall(evaluator.callId()));
                    }
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
            results.publishFinal(context.recorder(), budget, state.plan,
                    state.iteration, result);
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
        } catch (AgentInvocationStalledException stalled) {
            timeoutRequest(context.requestId());
            budget.settleUsage();
            context.recorder().sealAgainstLateCallbacks();
            workflowRecorder.terminalLifecycle("workflow_stalled",
                    "Agent call flow stopped after an Agent made no observable progress.",
                    lifecycle(namespace, "stalled", Map.of()));
            throw stalled;
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
            queue.addLast(new AgentCall(agents.resolve(handoff.target()).callId()));
            return;
        }
        AgentDecision.Delegate delegate = (AgentDecision.Delegate) decision;
        planPolicy.validate(delegate.workflow(), state.context.request());
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
        String nodeId = nodeId(parentNodeId, nodeKey);
        AiWorkflowType workflowType = AiWorkflowType.from(workflow);
        Map<String, Object> namespace = namespace(execution, workflow.id(), nodeId, parentNodeId,
                depth, workflow.members().size(), workflowType);
        AgentExecutionRecorder recorder = execution.recorder().fork(namespace);
        AgentWorkflowContext local = parent.inWorkflow(plan,
                new AgentWorkflowContext.Location(
                        workflow.id(), nodeId, parentNodeId, depth, workflowType));
        recorder.lifecycle("workflow_started", plan.guideMessage(),
                lifecycle(namespace, "started", Map.of(
                        "member_count", workflow.members().size())));

        try {
            announcePlannedAssignments(local, plan, workflow);
            List<WorkflowResult> results = graphScheduler.execute(
                    workflow, local.inputs(), budget::checkpoint,
                    (member, upstream) -> {
                        try (AiSpecialistAdmissionGate.Lease ignored = specialistAdmission.acquire(
                                local.request().requesterId(),
                                local.request().maximumAgents(), budget::checkpoint)) {
                            return executeMember(local, plan, member, upstream,
                                    nodeId, depth, budget);
                        }
                    });
            List<WorkflowResult> completed = results.stream()
                    .filter(WorkflowResult::successful).toList();
            if (completed.isEmpty()) {
                throw new IllegalStateException(
                        "Every member of Workflow " + workflow.id() + " failed.");
            }

            budget.checkpoint();
            AgentOutput output = this.results.synthesize(local, plan, results, budget);
            WorkflowResults.Completion completion = this.results.complete(
                    workflow.id(), nodeId, output, results);
            recorder.terminalLifecycle("workflow_completed", plan.synthesisGuideMessage(),
                    lifecycle(namespace, "completed", Map.of(
                            "completed", completion.completed(),
                            "failed", completion.directFailed(),
                            "failure_count", completion.failures(),
                            "partial_failure", completion.failures() > 0)));
            return completion.result();
        } catch (AgentOutputRetryHandoffException handoff) {
            recorder.terminalLifecycle("workflow_output_retry_handoff",
                    "Regenerating the response without tools.",
                    lifecycle(namespace, "retry_handoff", Map.of(
                            "agent_id", handoff.agentId().value())));
            throw handoff;
        } catch (AgentInvocationStalledException stalled) {
            recorder.terminalLifecycle("workflow_stalled",
                    "Workflow execution stopped after an Agent made no observable progress.",
                    lifecycle(namespace, "stalled", Map.of()));
            throw stalled;
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

    private void announcePlannedAssignments(AgentWorkflowContext local,
                                            AiWorkflowPlan plan,
                                            AiWorkflowPlan.WorkflowDefinition workflow) {
        for (AiWorkflowPlan.Member member : workflow.members()) {
            if (member.agent() == null) continue;
            Agent agent = agents.assigned(member.agent());
            AgentWorkflowContext assignment = local.withAssignment(
                    plan, member.id(), member.agent(), List.of());
            AgentAssignmentRun.planned(agent, assignment);
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
                 | AgentOutputRetryHandoffException failure) {
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
        Agent agent = agents.assigned(member.agent());
        AgentToolPolicy policy = planPolicy.assignmentTools(
                parent.execution(), member.agent().toolAccess());
        AgentWorkflowContext context = parent.withExecution(
                        parent.execution().forWorkflowAssignment(
                                policy, agent.id().value()))
                .withAssignment(plan, member.id(), member.agent(), upstream);
        try {
            budget.admitAssignment();
        } catch (RuntimeException rejected) {
            AgentAssignmentRun.rejected(agent, context, rejected);
            return WorkflowResult.failure(member.id(), rejected);
        }
        try {
            AgentDecision decision = budget.invoke(agents, agent.callId(), context);
            return decisionResult(decision, context, member.id(), budget, true);
        } catch (CancellationException | AgentGuardrailRefusedException
                 | AgentOutputRetryHandoffException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return WorkflowResult.failure(member.id(), failure);
        }
    }

    private WorkflowResult decisionResult(AgentDecision decision,
                                          AgentWorkflowContext context,
                                          String memberId, WorkflowRunBudget budget,
                                          boolean ownerDecision) {
        if (decision instanceof AgentDecision.Complete complete) {
            WorkflowResult result = WorkflowResult.success(
                    memberId, complete.result(), List.of());
            if (context.assignment() != null
                    && context.assignment().delegation() == AiWorkflowPlan.Delegation.FAN_OUT
                    && !ownerDecision) {
                AgentAssignmentRun.delegatedFinished(
                        agents.assigned(context.assignment()), context, result);
            }
            return result;
        }
        if (decision instanceof AgentDecision.Delegate delegate) {
            if (context.assignment() != null
                    && context.assignment().delegation() != AiWorkflowPlan.Delegation.FAN_OUT) {
                throw new IllegalStateException(
                        "A DIRECT Agent assignment cannot own a delegated Workflow.");
            }
            try {
                planPolicy.validate(delegate.workflow(), context.request().maximumAgents());
                AgentWorkflowContext.Location location = Objects.requireNonNull(
                        context.location(), "Workflow location");
                String agentNodeId = location.nodeId() + ":agent:" + memberId;
                WorkflowResult result = executeChild(
                        context, delegate.workflow().root(), agentNodeId,
                        location.depth() + 1, delegate.workflow(), memberId + "-delegated",
                        budget);
                AgentAssignmentRun.delegatedFinished(
                        agents.assigned(context.assignment()), context, result);
                return result;
            } catch (RuntimeException failure) {
                if (ownerDecision) {
                    AgentAssignmentRun.delegatedFinished(
                            agents.assigned(context.assignment()), context,
                            WorkflowResult.failure(memberId, failure));
                }
                throw failure;
            }
        }
        AgentDecision.Handoff handoff = (AgentDecision.Handoff) decision;
        AgentWorkflowContext next = handoff.feedback() != null
                ? context.withFeedback(handoff.feedback()) : context;
        try {
            return decisionResult(
                    budget.invoke(agents, agents.resolve(handoff.target()).callId(), next), next,
                    memberId, budget, false);
        } catch (RuntimeException failure) {
            if (ownerDecision && context.assignment() != null
                    && context.assignment().delegation() == AiWorkflowPlan.Delegation.FAN_OUT) {
                AgentAssignmentRun.delegatedFinished(
                        agents.assigned(context.assignment()), context,
                        WorkflowResult.failure(memberId, failure));
            }
            throw failure;
        }
    }

    private boolean shouldEvaluate(AgentWorkflowContext context) {
        // A delegation explicitly requested in this turn is an execution contract:
        // evaluating it as an open-ended draft can change the requested fan-out and
        // reopen the same Workflow after its answer has already been synthesized.
        return evaluator != null && !context.request().explicitDelegationRequested();
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

    private void requestProgress(String requestId) {
        if (requests != null) requests.progress(requestId);
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

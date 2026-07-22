package org.oagi.score.gateway.http.api.ai_management.workflow;

import jakarta.annotation.PreDestroy;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiBoundedAnswer;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiMultiAgentWorkerResult;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowEvaluation;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowNode;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowSynthesizerAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Executes the workflow authored by {@link AiWorkflowPlanner}. */
@Component
public final class AiWorkflowExecutionCoordinator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiWorkflowExecutionCoordinator.class);
    private static final int DEFAULT_MAX_CONCURRENT_SPECIALISTS = 16;
    private static final int DEFAULT_MAX_CONCURRENT_SPECIALISTS_PER_USER = 8;
    private static final Duration DEFAULT_SPECIALIST_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(10);
    private static final long MAX_SPECIALIST_RESULT_TOKENS = 4_000L;
    private static final long MIN_SPECIALIST_RESULT_TOKENS = 128L;
    private static final long SYNTHESIS_RESERVE_TOKENS = 64L;
    private static final int DEFAULT_MAX_WORKFLOW_ITERATIONS = 3;
    private static final int MAX_REPLAN_RESULT_LENGTH = 8_000;

    private final AiChatExecutor chatExecutor;
    private final AiWorkflowPlanner workflowPlanner;
    private final AiWorkflowEvaluator workflowEvaluator;
    private final AiAgentCatalog agents;
    private final AiContextBudgetService contextBudgets;
    private final AiRequestRegistry requests;
    private final AiMutationApprovalCoordinator approvalCoordinator;
    private final WorkflowSynthesizerAgent workflowSynthesizer;
    private final AiWorkflowInstructions instructions;
    private final Semaphore specialistAdmission;
    private final int maxConcurrentSpecialistsPerUser;
    private final Map<String, Semaphore> userAdmission = new ConcurrentHashMap<>();
    private final Duration specialistTimeout;
    private final List<AiWorkflowCompiler.WorkflowNodeCompiler> workflowCompilerExtensions;
    private int maximumWorkflowIterations = DEFAULT_MAX_WORKFLOW_ITERATIONS;
    private volatile ExecutorService executor;
    private boolean closed;

    private static AiWorkflowInstructions bundledInstructions() {
        return new AiWorkflowInstructions(
                new AiAgentCatalog(new DefaultResourceLoader()));
    }

    @Autowired
    public AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiWorkflowPlanner workflowPlanner,
                               AiWorkflowEvaluator workflowEvaluator, AiAgentCatalog agents,
                               ScoreAiProperties properties,
                               AiContextBudgetService contextBudgets, AiRequestRegistry requests,
                               AiMutationApprovalCoordinator approvalCoordinator,
                               WorkflowSynthesizerAgent workflowSynthesizer,
                               AiWorkflowInstructions instructions,
                               ObjectProvider<AiWorkflowCompiler.WorkflowNodeCompiler> workflowCompilerExtensions) {
        this(chatExecutor, workflowPlanner, workflowEvaluator, agents, contextBudgets, requests,
                approvalCoordinator, workflowSynthesizer, instructions,
                properties != null ? properties.getMultiAgent().getMaxConcurrentSpecialists()
                        : DEFAULT_MAX_CONCURRENT_SPECIALISTS,
                properties != null ? properties.getMultiAgent().getMaxConcurrentSpecialistsPerUser()
                        : DEFAULT_MAX_CONCURRENT_SPECIALISTS_PER_USER,
                properties != null ? properties.getMultiAgent().getSpecialistTimeout()
                        : DEFAULT_SPECIALIST_TIMEOUT,
                workflowCompilerExtensions != null
                        ? workflowCompilerExtensions.orderedStream().toList() : List.of());
        int configuredIterations = properties != null
                ? properties.getMultiAgent().getMaximumWorkflowIterations()
                : DEFAULT_MAX_WORKFLOW_ITERATIONS;
        if (configuredIterations < 1) {
            throw new IllegalArgumentException(
                    "score.ai.multi-agent.maximum-workflow-iterations must be positive.");
        }
        this.maximumWorkflowIterations = configuredIterations;
    }

    /** Compatibility constructor for focused tests and non-Spring callers. */
    public AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor) {
        this(chatExecutor, null, null, null, null, null, null, DEFAULT_MAX_CONCURRENT_SPECIALISTS,
                DEFAULT_MAX_CONCURRENT_SPECIALISTS_PER_USER, DEFAULT_SPECIALIST_TIMEOUT, List.of());
    }

    public AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor,
                                          AiContextBudgetService contextBudgets,
                                          AiRequestRegistry requests) {
        this(chatExecutor, null, null, null, contextBudgets, requests, null, DEFAULT_MAX_CONCURRENT_SPECIALISTS,
                DEFAULT_MAX_CONCURRENT_SPECIALISTS_PER_USER, DEFAULT_SPECIALIST_TIMEOUT, List.of());
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiContextBudgetService contextBudgets,
                        int maxConcurrentSpecialists, Duration specialistTimeout) {
        this(chatExecutor, null, null, null, contextBudgets, null, null, maxConcurrentSpecialists,
                maxConcurrentSpecialists, specialistTimeout, List.of());
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiContextBudgetService contextBudgets,
                        AiRequestRegistry requests, int maxConcurrentSpecialists,
                        Duration specialistTimeout) {
        this(chatExecutor, null, null, null, contextBudgets, requests, null, maxConcurrentSpecialists,
                maxConcurrentSpecialists, specialistTimeout, List.of());
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiContextBudgetService contextBudgets,
                        AiRequestRegistry requests, int maxConcurrentSpecialists,
                        int maxConcurrentSpecialistsPerUser, Duration specialistTimeout) {
        this(chatExecutor, null, null, null, contextBudgets, requests, null, maxConcurrentSpecialists,
                maxConcurrentSpecialistsPerUser, specialistTimeout, List.of());
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiWorkflowPlanner workflowPlanner,
                        AiAgentCatalog agents, AiContextBudgetService contextBudgets,
                        AiRequestRegistry requests, int maxConcurrentSpecialists,
                        int maxConcurrentSpecialistsPerUser, Duration specialistTimeout) {
        this(chatExecutor, workflowPlanner, null, agents, contextBudgets, requests, null,
                maxConcurrentSpecialists, maxConcurrentSpecialistsPerUser, specialistTimeout, List.of());
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiWorkflowPlanner workflowPlanner,
                        AiWorkflowEvaluator workflowEvaluator, AiAgentCatalog agents,
                        AiContextBudgetService contextBudgets, AiRequestRegistry requests,
                        int maxConcurrentSpecialists, int maxConcurrentSpecialistsPerUser,
                        Duration specialistTimeout) {
        this(chatExecutor, workflowPlanner, workflowEvaluator, agents, contextBudgets, requests, null,
                maxConcurrentSpecialists, maxConcurrentSpecialistsPerUser, specialistTimeout,
                List.of());
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiWorkflowPlanner workflowPlanner,
                        AiWorkflowEvaluator workflowEvaluator, AiAgentCatalog agents,
                        AiContextBudgetService contextBudgets, AiRequestRegistry requests,
                        AiMutationApprovalCoordinator approvalCoordinator,
                        int maxConcurrentSpecialists, int maxConcurrentSpecialistsPerUser,
                        Duration specialistTimeout,
                        List<AiWorkflowCompiler.WorkflowNodeCompiler> workflowCompilerExtensions) {
        this(chatExecutor, workflowPlanner, workflowEvaluator, agents, contextBudgets, requests,
                approvalCoordinator, null, bundledInstructions(), maxConcurrentSpecialists,
                maxConcurrentSpecialistsPerUser, specialistTimeout, workflowCompilerExtensions);
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiWorkflowPlanner workflowPlanner,
                        AiWorkflowEvaluator workflowEvaluator, AiAgentCatalog agents,
                        AiContextBudgetService contextBudgets, AiRequestRegistry requests,
                        AiMutationApprovalCoordinator approvalCoordinator,
                        WorkflowSynthesizerAgent workflowSynthesizer,
                        int maxConcurrentSpecialists, int maxConcurrentSpecialistsPerUser,
                        Duration specialistTimeout,
                        List<AiWorkflowCompiler.WorkflowNodeCompiler> workflowCompilerExtensions) {
        this(chatExecutor, workflowPlanner, workflowEvaluator, agents, contextBudgets, requests,
                approvalCoordinator, workflowSynthesizer, bundledInstructions(),
                maxConcurrentSpecialists, maxConcurrentSpecialistsPerUser,
                specialistTimeout, workflowCompilerExtensions);
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiWorkflowPlanner workflowPlanner,
                        AiWorkflowEvaluator workflowEvaluator, AiAgentCatalog agents,
                        AiContextBudgetService contextBudgets, AiRequestRegistry requests,
                        AiMutationApprovalCoordinator approvalCoordinator,
                        WorkflowSynthesizerAgent workflowSynthesizer,
                        AiWorkflowInstructions instructions,
                        int maxConcurrentSpecialists, int maxConcurrentSpecialistsPerUser,
                        Duration specialistTimeout,
                        List<AiWorkflowCompiler.WorkflowNodeCompiler> workflowCompilerExtensions) {
        this.chatExecutor = chatExecutor;
        this.workflowPlanner = workflowPlanner;
        this.workflowEvaluator = workflowEvaluator;
        this.agents = agents;
        if (maxConcurrentSpecialists < 1 || maxConcurrentSpecialistsPerUser < 1) {
            throw new IllegalArgumentException("AI specialist concurrency limits must be positive.");
        }
        if (specialistTimeout == null || specialistTimeout.isZero() || specialistTimeout.isNegative()) {
            throw new IllegalArgumentException("score.ai.multi-agent.specialist-timeout must be positive.");
        }
        this.contextBudgets = contextBudgets;
        this.requests = requests;
        this.approvalCoordinator = approvalCoordinator;
        this.workflowSynthesizer = workflowSynthesizer;
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.specialistAdmission = new Semaphore(maxConcurrentSpecialists, true);
        this.maxConcurrentSpecialistsPerUser = maxConcurrentSpecialistsPerUser;
        this.specialistTimeout = specialistTimeout;
        this.workflowCompilerExtensions = workflowCompilerExtensions != null
                ? List.copyOf(workflowCompilerExtensions) : List.of();
    }

    AiWorkflowExecutionCoordinator(AiChatExecutor chatExecutor, AiWorkflowPlanner workflowPlanner,
                        AiWorkflowEvaluator workflowEvaluator, AiAgentCatalog agents,
                        AiContextBudgetService contextBudgets, AiRequestRegistry requests,
                        int maxConcurrentSpecialists, int maxConcurrentSpecialistsPerUser,
                        Duration specialistTimeout,
                        List<AiWorkflowCompiler.WorkflowNodeCompiler> workflowCompilerExtensions) {
        this(chatExecutor, workflowPlanner, workflowEvaluator, agents, contextBudgets, requests,
                null, maxConcurrentSpecialists, maxConcurrentSpecialistsPerUser,
                specialistTimeout, workflowCompilerExtensions);
    }

    public AiChatExecutor.Result execute(AiChatExecutor.Context context) {
        if (chatExecutor == null) {
            throw new IllegalStateException("No AI chat executor is configured.");
        }
        if (context.agentDepth() != 0) {
            throw new IllegalArgumentException("Nested agent delegation is not allowed.");
        }
        if (workflowPlanner != null && workflowEvaluator != null) {
            return executeEvaluatorOptimizerLoop(context);
        }
        AiWorkflowPlan plan = workflowPlanner != null
                ? workflowPlanner.plan(context) : fallbackPlan(context);
        return executePlan(context, plan);
    }

    private AiChatExecutor.Result executePlan(AiChatExecutor.Context context, AiWorkflowPlan plan) {
        return executePlan(context, plan, 0);
    }

    private AiChatExecutor.Result executePlan(
            AiChatExecutor.Context context, AiWorkflowPlan plan, int workflowIteration) {
        if (plan.root() != null) {
            return executeComposedWorkflow(context, plan, workflowIteration);
        }
        if (!plan.toolsNeeded() && plan.tasks().isEmpty()) {
            return chatExecutor.execute(new AiChatExecutor.Context(
                    context.request().withMultiAgent(AiMultiAgentOptions.single()), context.history(),
                    context.userMessage(), context.requester(), context.recorder(), false,
                    context.streamVisibleContent(), AiChatExecutor.ToolPolicy.NONE, 0,
                    context.approvalScope())
                    .withAgentIdentity(context.agentId(), context.executionPurpose())
                    .withGuardrailDecisions(context.guardrailDecisionIds()));
        }
        if (StringUtils.hasText(plan.guideMessage())) {
            context.recorder().guide(plan.guideMessage(), Map.of(
                    "workflow", plan.workflow(), "active_verb", plan.activeVerb(),
                    "completed_verb", plan.completedVerb()));
        }
        if (plan.tasks().isEmpty()) {
            return executeDirectToolWorkflow(context, plan);
        }
        AiChatExecutor.Context delegatedContext = plan.toolsNeeded() ? context : new AiChatExecutor.Context(
                context.request(), context.history(), context.userMessage(), context.requester(),
                context.recorder(), false, context.streamVisibleContent(),
                AiChatExecutor.ToolPolicy.NONE, context.agentDepth(), context.approvalScope())
                .withAgentIdentity(context.agentId(), context.executionPurpose())
                .withGuardrailDecisions(context.guardrailDecisionIds());
        return executeDelegatedWorkflow(delegatedContext, plan, workflowIteration);
    }

    private AiChatExecutor.Result executeEvaluatorOptimizerLoop(AiChatExecutor.Context context) {
        List<AiWorkflowFeedback> feedback = new ArrayList<>();
        List<AiWorkflowPlan> plans = new ArrayList<>();
        AiChatExecutor.Context buffered = withVisibleStreaming(context, false);
        EvaluatorOptimizerWorkflow loop = new EvaluatorOptimizerWorkflow(
                context.request().requestId() + ":evaluator-optimizer",
                maximumWorkflowIterations,
                (iterationContext, iteration, previousAttempts) -> {
                    AiChatExecutor.Context iterationExecution = legacyContext(iterationContext);
                    interruptFence(iterationExecution.recorder(),
                            context.request().requestId(), "before_iteration_" + iteration,
                            Map.of("execution_kind", "evaluator_optimizer"),
                            fallbackPlan(iterationExecution));
                    if (iteration > 1) {
                        iterationExecution.recorder().resetGuideDeduplication();
                    }
                    AiWorkflowPlan plan = workflowPlanner.plan(
                            iterationExecution, List.copyOf(feedback));
                    plans.add(plan);
                    return plannedWorkflow(plan, iteration);
                },
                (iterationContext, result, iteration) -> {
                    AiWorkflowPlan plan = plans.get(iteration - 1);
                    AiChatExecutor.Context iterationExecution = legacyContext(iterationContext);
                    AiWorkflowEvaluation evaluation = workflowEvaluator.evaluate(
                            iterationExecution, plan,
                            new AiChatExecutor.Result(result.output(), result.metadata()),
                            iteration, maximumWorkflowIterations);
                    if (!evaluation.complete()) {
                        feedback.add(new AiWorkflowFeedback(iteration, plan.workflow(),
                                boundedReplanText(result.output()), evaluation.feedback(),
                                evaluation.nextObjective()));
                    }
                    return new EvaluatorOptimizerWorkflow.Evaluation(
                            evaluation.complete(), evaluation.feedback(),
                            evaluation.nextObjective());
                },
                this::nextIterationContext);
        WorkflowResult result = loop.process(WorkflowContext.root(workflowInvocation(buffered)));
        return new AiChatExecutor.Result(result.output(), result.metadata());
    }

    private Workflow plannedWorkflow(AiWorkflowPlan plan, int iteration) {
        return new DirectWorkflow("planned-iteration-" + iteration, workflowContext -> {
            AiChatExecutor.Result result = executePlan(
                    legacyContext(workflowContext), plan, iteration);
            Map<String, Object> metadata = new LinkedHashMap<>(result.traceMetadata());
            metadata.putIfAbsent("workflow", plan.workflow());
            metadata.put("workflow_iteration", iteration);
            return WorkflowResult.success("planned-iteration-" + iteration,
                    result.answer(), metadata, List.of());
        });
    }

    private WorkflowContext nextIterationContext(
            WorkflowContext context, WorkflowResult result,
            EvaluatorOptimizerWorkflow.Evaluation evaluation, int iteration) {
        List<Message> history = new ArrayList<>(legacyContext(context).history());
        history.add(untrustedReference(instructions.render(
                AiWorkflowInstructions.Template.REPLAN_CONTEXT, Map.of(
                        "iteration", iteration,
                        "feedback", Objects.toString(evaluation.feedback(), "none"),
                        "nextObjective", Objects.toString(evaluation.nextObjective(), "none"),
                        "priorResult", boundedReplanText(result.output()))).value()));
        AiChatExecutor.Context current = legacyContext(context);
        AiChatExecutor.Context next = new AiChatExecutor.Context(
                current.request(), history, current.userMessage(), current.requester(),
                current.recorder(), current.toolsEnabled(), false,
                current.toolPolicy(), current.agentDepth(), current.approvalScope())
                .withAgentIdentity(current.agentId(), current.executionPurpose())
                .withGuardrailDecisions(current.guardrailDecisionIds());
        return new WorkflowContext(workflowInvocation(next), List.of(result));
    }

    private AiChatExecutor.Context withVisibleStreaming(AiChatExecutor.Context context, boolean visible) {
        return new AiChatExecutor.Context(context.request(), context.history(), context.userMessage(),
                context.requester(), context.recorder(), context.toolsEnabled(), visible,
                context.toolPolicy(), context.agentDepth(), context.approvalScope())
                .withAgentIdentity(context.agentId(), context.executionPurpose())
                .withGuardrailDecisions(context.guardrailDecisionIds());
    }

    private String boundedReplanText(String value) {
        String result = Objects.requireNonNullElse(value, "");
        return result.length() <= MAX_REPLAN_RESULT_LENGTH
                ? result : result.substring(0, MAX_REPLAN_RESULT_LENGTH).stripTrailing()
                + "\n[PRIOR RESULT TRUNCATED]";
    }

    private AiChatExecutor.Result executeComposedWorkflow(
            AiChatExecutor.Context context, AiWorkflowPlan plan, int workflowIteration) {
        if (StringUtils.hasText(plan.guideMessage())) {
            context.recorder().guide(plan.guideMessage(), Map.of(
                    "workflow", plan.workflow(), "active_verb", plan.activeVerb(),
                    "completed_verb", plan.completedVerb()));
        }
        ComposedExecution execution = createComposedExecution(
                context, plan.root(), workflowIteration);
        int plannedSpecialists = execution.controls().size();
        AiTrajectoryRecorder leadRecorder = null;
        Map<String, Object> leadNamespace = Map.of();
        if (plannedSpecialists > 0) {
            leadNamespace = leadNamespace(execution.fanoutId(), execution.leadNodeId(),
                    plan, context.request().multiAgent(), "multi_agent", plannedSpecialists);
            leadRecorder = Objects.requireNonNull(context.recorder().fork(leadNamespace),
                    "The composed workflow lead recorder is required.");
            leadRecorder.lifecycle("multi_agent_started", composedLeadStatus(plan), lifecycleMetadata(
                    leadNamespace, plan.activeVerb(), plan.completedVerb(), "started",
                    Map.of("agent_count", plannedSpecialists)));
            execution.controls().values().forEach(this::recordPlannedWorker);
        }
        AiTrajectoryRecorder finalLeadRecorder = leadRecorder;
        Map<String, Object> finalLeadNamespace = leadNamespace;
        try {
            Workflow workflow = new AiWorkflowCompiler(executor(), executionTimeout(),
                    (workflowContext, node) -> executeComposedLeaf(
                            workflowContext, node, execution),
                    this::aggregateComposedResults,
                    workflowCompilerExtensions, parallelApprovalLifecycle())
                    .compile(plan.root());
            WorkflowResult result = workflow.process(WorkflowContext.root(workflowInvocation(context)));
            if (composedCancellationObserved(context, execution)) {
                throw new CancellationException("The composed workflow was cancelled.");
            }
            Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
            metadata.put("workflow", plan.workflow());
            metadata.put("active_verb", plan.activeVerb());
            metadata.put("completed_verb", plan.completedVerb());
            if (finalLeadRecorder != null) {
                finalLeadRecorder.terminalLifecycle("multi_agent_completed",
                        sentence(plan.completedVerb()), lifecycleMetadata(
                                finalLeadNamespace, plan.activeVerb(), plan.completedVerb(),
                                "completed", Map.of("agent_count", plannedSpecialists)));
            }
            return new AiChatExecutor.Result(result.output(), metadata);
        } catch (CancellationException failure) {
            recordComposedCancellation(execution, finalLeadRecorder,
                    finalLeadNamespace, plan, plannedSpecialists);
            throw failure;
        } catch (RuntimeException failure) {
            if (composedCancellationObserved(context, execution)) {
                recordComposedCancellation(execution, finalLeadRecorder,
                        finalLeadNamespace, plan, plannedSpecialists);
                CancellationException cancellation = new CancellationException(
                        "The composed workflow was cancelled.");
                cancellation.initCause(failure);
                throw cancellation;
            }
            failOutstanding(execution.controls().values(), "workflow_failed");
            if (finalLeadRecorder != null) {
                finalLeadRecorder.terminalLifecycle("multi_agent_failed",
                        "The composed workflow failed.", lifecycleMetadata(
                                finalLeadNamespace, plan.activeVerb(), plan.completedVerb(), "failed",
                                Map.of("agent_count", plannedSpecialists,
                                        "reason", failure.getClass().getSimpleName())));
            }
            throw failure;
        }
    }

    private ComposedExecution createComposedExecution(
            AiChatExecutor.Context context, AiWorkflowNode root, int workflowIteration) {
        String fanoutId = composedFanoutId(
                context.request().requestId(), workflowIteration);
        String leadNodeId = fanoutId + ":lead";
        Map<String, PlannedComposedNode> planned = new LinkedHashMap<>();
        collectComposedActivities(root, false, planned);
        List<PreparedComposedControl> prepared = new ArrayList<>();
        int ordinal = 0;
        for (PlannedComposedNode plannedNode : planned.values()) {
            AiWorkflowNode node = plannedNode.node();
            AiWorkflowPlan.Task task;
            AiAgentDefinition definition;
            if (plannedNode.registeredWorker()) {
                task = node.task();
                definition = agents.requireWorker(task.agentId());
            } else {
                String branchId = "workflow-branch-" + node.id();
                definition = new AiAgentDefinition(
                        branchId, "Workflow branch", "concurrent read-only task",
                        "Execute the planned workflow branch.");
                task = new AiWorkflowPlan.Task(
                        node.id(), branchId, definition.instruction(), node.guideMessage(),
                        node.activeVerb(), node.completedVerb(), AiWorkflowPlan.ToolAccess.READ_ONLY);
            }
            prepared.add(new PreparedComposedControl(
                    node, task, definition, plannedNode.registeredWorker(), ++ordinal));
        }
        Map<String, WorkerControl> controls = new LinkedHashMap<>();
        for (PreparedComposedControl item : prepared) {
            AiWorkflowNode node = item.node();
            AiWorkflowPlan.Task task = item.task();
            AiAgentDefinition definition = item.definition();
            String nodeId = fanoutId + ":worker:" + node.id();
            Map<String, Object> namespace = workerNamespace(
                    fanoutId, nodeId, leadNodeId, WorkflowTypes.DIRECT,
                    "multi_agent", definition, task, item.ordinal());
            AiTrajectoryRecorder recorder = Objects.requireNonNull(
                    context.recorder().forkSubagent(
                            definition.id(), task.instruction(), namespace),
                    "The composed workflow worker recorder is required.");
            WorkerControl control = new WorkerControl(
                    item.ordinal(), task, definition, "multi_agent",
                    recorder.conversationId(),
                    recorder, namespace, new AtomicReference<>(),
                    new AtomicBoolean(), new AtomicReference<>());
            controls.put(node.id(), control);
        }
        return new ComposedExecution(fanoutId, leadNodeId,
                Collections.unmodifiableMap(new LinkedHashMap<>(controls)));
    }

    private void collectComposedActivities(
            AiWorkflowNode node, boolean concurrent,
            Map<String, PlannedComposedNode> planned) {
        if (node == null) return;
        if (node.task() != null) {
            addComposedActivity(planned, node, true);
            return;
        }
        if (WorkflowTypes.ROUTING.equals(node.workflow())) {
            collectComposedActivities(
                    node.routes().get(node.selectedRoute()), concurrent, planned);
            return;
        }
        if (concurrent && WorkflowTypes.DIRECT.equals(node.workflow())
                && node.children().isEmpty()) {
            addComposedActivity(planned, node, false);
            return;
        }
        boolean concurrentChildren = concurrent
                || WorkflowTypes.PARALLEL.equals(node.workflow())
                || WorkflowTypes.ORCHESTRATOR_WORKERS.equals(node.workflow());
        node.children().forEach(child ->
                collectComposedActivities(child, concurrentChildren, planned));
    }

    private void addComposedActivity(
            Map<String, PlannedComposedNode> planned, AiWorkflowNode node,
            boolean registeredWorker) {
        if (planned.putIfAbsent(node.id(),
                new PlannedComposedNode(node, registeredWorker)) != null) {
            throw new IllegalArgumentException(
                    "Composed workflow node ids must be unique: " + node.id());
        }
    }

    private String composedFanoutId(String requestId, int workflowIteration) {
        String base = requestId + ":composed";
        return workflowIteration > 0 ? base + ":iteration-" + workflowIteration : base;
    }

    private String composedLeadStatus(AiWorkflowPlan plan) {
        return sentence(StringUtils.hasText(plan.guideMessage())
                ? plan.guideMessage() : plan.activeVerb());
    }

    private boolean composedCancellationObserved(
            AiChatExecutor.Context context, ComposedExecution execution) {
        return requestStopping(context.request().requestId())
                || execution.controls().values().stream().anyMatch(this::cancelled);
    }

    private void recordComposedCancellation(
            ComposedExecution execution, AiTrajectoryRecorder leadRecorder,
            Map<String, Object> leadNamespace, AiWorkflowPlan plan, int plannedSpecialists) {
        cancelOutstanding(execution.controls().values(), "cancelled");
        if (leadRecorder != null) {
            leadRecorder.terminalLifecycle("multi_agent_cancelled",
                    "The composed workflow was cancelled.", lifecycleMetadata(
                            leadNamespace, plan.activeVerb(), plan.completedVerb(),
                            "cancelled", Map.of("agent_count", plannedSpecialists)));
        }
    }

    private WorkflowResult executeComposedLeaf(
            WorkflowContext workflowContext, AiWorkflowNode node,
            ComposedExecution execution) {
        if (node.task() != null) {
            return executeComposedWorker(legacyContext(workflowContext), node,
                    workflowContext.upstreamResults(), execution);
        }
        AiChatExecutor.Context context = withWorkflowResults(
                legacyContext(workflowContext), workflowContext.upstreamResults());
        if (containsMutationWorkerResult(workflowContext.upstreamResults())) {
            context = withToolPolicy(context,
                    context.toolPolicy() == AiChatExecutor.ToolPolicy.NONE
                            ? AiChatExecutor.ToolPolicy.NONE
                            : AiChatExecutor.ToolPolicy.READ_ONLY);
        }
        if (workflowContext.concurrent()) {
            return executeConcurrentDirectLeaf(context, node, execution);
        }
        cancellationFence(context.request().requestId(), "before_leaf_" + node.id());
        AiWorkflowPlan leafPlan = new AiWorkflowPlan(WorkflowTypes.DIRECT, node.toolsNeeded(),
                node.guideMessage(), node.activeVerb(), node.completedVerb(),
                node.synthesisGuideMessage(), node.synthesisActiveVerb(),
                node.synthesisCompletedVerb(), List.of());
        AiChatExecutor.Result result = executePlan(context, leafPlan);
        return WorkflowResult.success(node.id(), result.answer(),
                result.traceMetadata(), List.of());
    }

    /**
     * A direct leaf that executes concurrently with siblings is not the exclusive lead:
     * it occupies a specialist slot and must not mutate, matching the worker invariants.
     */
    private WorkflowResult executeConcurrentDirectLeaf(
            AiChatExecutor.Context context, AiWorkflowNode node,
            ComposedExecution execution) {
        WorkerControl control = Objects.requireNonNull(execution.controls().get(node.id()),
                "The concurrent workflow branch control is required for " + node.id());
        AiTrajectoryRecorder recorder = control.recorder();
        start(control);
        try {
            return admitConcurrentExecution(context, () -> {
                AiChatExecutor.ToolPolicy policy = node.toolsNeeded()
                        ? AiChatExecutor.ToolPolicy.READ_ONLY : AiChatExecutor.ToolPolicy.NONE;
                AiChatExecutor.Context leaf = new AiChatExecutor.Context(
                        context.request().withConversationId(control.childConversationId())
                                .withMultiAgent(AiMultiAgentOptions.single()),
                        context.history(), context.userMessage(), context.requester(),
                        recorder, policy != AiChatExecutor.ToolPolicy.NONE, false, policy, 1,
                        context.approvalScope())
                        .withAgentIdentity(control.definition().id(), ExecutionScope.Purpose.WORKER)
                        .withGuardrailDecisions(context.guardrailDecisionIds());
                AiChatExecutor.Result result = executeGroundedModel(
                        leaf, node.toolsNeeded(), workerRecovery(AiChatExecutor.ToolPolicy.READ_ONLY),
                        "The concurrent direct workflow branch completed no successful "
                                + "connectCenter domain tool call.");
                AiBoundedAnswer bounded = bounded(result.answer(), MAX_SPECIALIST_RESULT_TOKENS);
                Map<String, Object> metadata = new LinkedHashMap<>(result.traceMetadata());
                metadata.putAll(control.namespace());
                metadata.put("tool_policy", policy.name());
                markCompleted(control, bounded);
                return WorkflowResult.success(node.id(), bounded.value(), metadata, List.of());
            });
        } catch (CancellationException failure) {
            markCancelled(control, "cancelled");
            throw failure;
        } catch (RuntimeException failure) {
            markFailed(control, "execution_failure");
            throw failure;
        }
    }

    /**
     * Runs one simultaneously executing model call under the same fair admission
     * slots as a registered worker: the per-user semaphore first, then the global
     * one, both bounded by the specialist timeout and the stop fence.
     */
    private <T> T admitConcurrentExecution(
            AiChatExecutor.Context context, java.util.function.Supplier<T> execution) {
        Semaphore userSlot = userAdmission(context.requester());
        boolean userAdmitted = false;
        boolean globallyAdmitted = false;
        try {
            long deadline = deadlineNanos(specialistTimeout);
            long remaining = remainingNanos(deadline);
            if (remaining <= 0 || !userSlot.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("Workflow branch admission timed out.");
            }
            userAdmitted = true;
            remaining = remainingNanos(deadline);
            if (remaining <= 0 || !specialistAdmission.tryAcquire(
                    remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("Workflow branch admission timed out.");
            }
            globallyAdmitted = true;
            if (requestStopping(context.request().requestId())) {
                throw new CancellationException("Workflow branch was cancelled.");
            }
            return execution.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Workflow branch was interrupted.");
        } finally {
            if (globallyAdmitted) specialistAdmission.release();
            if (userAdmitted) userSlot.release();
        }
    }

    private WorkflowResult executeComposedWorker(
            AiChatExecutor.Context context, AiWorkflowNode node, List<WorkflowResult> upstream,
            ComposedExecution execution) {
        AiWorkflowPlan.Task task = node.task();
        WorkerControl control = Objects.requireNonNull(execution.controls().get(node.id()),
                "The composed workflow worker control is required for " + node.id());
        AiAgentDefinition definition = control.definition();
        AiTrajectoryRecorder recorder = control.recorder();
        start(control);

        AiChatExecutor.ToolPolicy policy = workerPolicy(
                task, node.toolsNeeded(), context);
        long deadline = deadlineNanos(policy == AiChatExecutor.ToolPolicy.FULL
                ? executionTimeout() : specialistTimeout);
        try (SpecialistAdmissionLease admission = new SpecialistAdmissionLease(
                context.requester(), context.request().requestId(), deadline)) {
            if (!admission.tryAcquire()) {
                markFailed(control, "admission_timeout");
                throw new IllegalStateException("Workflow worker admission timed out.");
            }
            if (requestStopping(context.request().requestId())) {
                markCancelled(control, "cancelled");
                throw new CancellationException("Workflow worker was cancelled.");
            }
            List<Message> workerHistory = new ArrayList<>();
            workerHistory.add(new SystemMessage(composedWorkerPrompt(definition, task, policy)));
            workerHistory.add(originalRequestReference(context.userMessage()));
            String upstreamReference = upstreamReferenceText(upstream);
            if (upstreamReference != null) {
                workerHistory.add(untrustedReference(upstreamReference));
            }
            AiChatExecutor.Context child = new AiChatExecutor.Context(
                    context.request().withConversationId(control.childConversationId())
                            .withMultiAgent(AiMultiAgentOptions.single()),
                    workerHistory, workerAssignmentMessage(context.userMessage(), task),
                    context.requester(), recorder, policy != AiChatExecutor.ToolPolicy.NONE,
                    false, policy, 1,
                    composedApprovalScope(context, node, definition, task,
                            control.childConversationId()), admission)
                    .withAgentIdentity(definition.id(),
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.WORKER)
                    .withGuardrailDecisions(context.guardrailDecisionIds());
            AiChatExecutor.Result answer = executeGroundedModel(
                    child, policy != AiChatExecutor.ToolPolicy.NONE, workerRecovery(policy),
                    "The delegated worker completed no successful connectCenter domain tool call.");
            AiBoundedAnswer bounded = bounded(answer.answer(), MAX_SPECIALIST_RESULT_TOKENS);
            Map<String, Object> metadata = new LinkedHashMap<>(answer.traceMetadata());
            metadata.putAll(control.namespace());
            metadata.put("active_verb", task.activeVerb());
            metadata.put("completed_verb", task.completedVerb());
            metadata.put("tool_policy", policy.name());
            if (policy == AiChatExecutor.ToolPolicy.FULL) {
                metadata.put("mutation_worker", true);
            }
            markCompleted(control, bounded);
            return WorkflowResult.success(node.id(), bounded.value(), metadata, List.of());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            markCancelled(control, "interrupted");
            throw new CancellationException("Workflow worker was interrupted.");
        } catch (CancellationException failure) {
            markCancelled(control, "cancelled");
            throw failure;
        } catch (RuntimeException failure) {
            markFailed(control, "execution_failure");
            throw failure;
        }
    }

    private String composedWorkerPrompt(
            AiAgentDefinition definition, AiWorkflowPlan.Task task,
            AiChatExecutor.ToolPolicy policy) {
        return workerInstruction(definition, task, policy);
    }

    private WorkflowResult aggregateComposedResults(
            WorkflowContext workflowContext, AiWorkflowNode node,
            List<WorkflowResult> results) {
        if (results.stream().anyMatch(this::wasCancelled)) {
            throw new CancellationException(
                    "A composed workflow branch was cancelled.");
        }
        List<WorkflowResult> completed = results.stream()
                .filter(WorkflowResult::successful).toList();
        if (completed.isEmpty()) {
            throw new IllegalStateException("All composed workflow children failed.");
        }
        // Upstream chain evidence and the children being synthesized are distinct
        // inputs: children appear once, in the synthesis block only.
        AiChatExecutor.Context context = withWorkflowResults(
                legacyContext(workflowContext), workflowContext.upstreamResults());
        List<Message> history = new ArrayList<>(context.history());
        history.add(untrustedReference(composedSynthesisPrompt(node, results)));
        verifySynthesisBudget(context, history);
        // A container nested inside another concurrent container synthesizes while
        // outer siblings still run: it is not the exclusive lead, so its synthesis
        // is read-only and occupies a specialist slot exactly like a worker.
        boolean concurrent = workflowContext.concurrent();
        AiChatExecutor.ToolPolicy policy = synthesisPolicy(
                context, node.toolsNeeded(), containsMutationWorker(node) || concurrent);
        AiTrajectoryRecorder recorder = concurrent
                ? context.recorder().fork(Map.of(
                        "node_id", context.request().requestId() + ":" + node.id() + ":synthesis",
                        "workflow", node.workflow(),
                        "concurrent_branch", true,
                        "depth", 1))
                : context.recorder();
        String guide = StringUtils.hasText(node.synthesisGuideMessage())
                ? node.synthesisGuideMessage() : node.guideMessage();
        if (StringUtils.hasText(guide)) {
            recorder.guide(guide, Map.of(
                    "workflow", node.workflow(),
                    "active_verb", node.synthesisActiveVerb(),
                    "completed_verb", node.synthesisCompletedVerb()));
        }
        AiChatExecutor.Context synthesisContext;
        if (concurrent) {
            var synthesizer = Objects.requireNonNull(workflowSynthesizer,
                    "The workflow Synthesizer Agent is required for nested synthesis.");
            history.addFirst(new SystemMessage(
                    synthesizer.definition().instruction().render().value()));
            synthesisContext = new AiChatExecutor.Context(
                    context.request().withMultiAgent(AiMultiAgentOptions.single()),
                    history, context.userMessage(), context.requester(), recorder,
                    policy != AiChatExecutor.ToolPolicy.NONE, false, policy, 1,
                    context.approvalScope())
                    .withAgentIdentity(synthesizer.id().value(),
                            ExecutionScope.Purpose.SYNTHESIS);
        } else {
            synthesisContext = new AiChatExecutor.Context(
                    context.request().withMultiAgent(AiMultiAgentOptions.single()),
                    history, context.userMessage(), context.requester(), recorder,
                    policy != AiChatExecutor.ToolPolicy.NONE, false, policy, 0,
                    context.approvalScope())
                    .withAgentIdentity(context.agentId(), context.executionPurpose());
        }
        synthesisContext = synthesisContext.withGuardrailDecisions(
                context.guardrailDecisionIds());
        AiChatExecutor.Context finalSynthesisContext = synthesisContext;
        java.util.function.Supplier<AiChatExecutor.Result> synthesis =
                () -> chatExecutor.execute(finalSynthesisContext);
        AiChatExecutor.Result answer = concurrent
                ? admitConcurrentExecution(context, synthesis) : synthesis.get();
        Map<String, Object> metadata = new LinkedHashMap<>(answer.traceMetadata());
        metadata.put("workflow", node.workflow());
        metadata.put("workflow_node", node.id());
        metadata.put("tool_policy", policy.name());
        metadata.put("completed_children", completed.size());
        metadata.put("failed_children", results.size() - completed.size());
        return WorkflowResult.success(node.id(), answer.answer(), metadata, results);
    }

    private boolean wasCancelled(WorkflowResult result) {
        if (result == null) return false;
        Throwable failure = result.failure();
        while (failure != null) {
            if (failure instanceof CancellationException) return true;
            failure = failure.getCause();
        }
        return result.children().stream().anyMatch(this::wasCancelled);
    }

    private AiChatExecutor.Context withWorkflowResults(
            AiChatExecutor.Context context, List<WorkflowResult> results) {
        String reference = upstreamReferenceText(results);
        if (reference == null) return context;
        List<Message> history = new ArrayList<>(context.history());
        history.add(untrustedReference(reference));
        return new AiChatExecutor.Context(context.request(), history, context.userMessage(),
                context.requester(), context.recorder(), context.toolsEnabled(), false,
                context.toolPolicy(), context.agentDepth(), context.approvalScope())
                .withAgentIdentity(context.agentId(), context.executionPurpose())
                .withGuardrailDecisions(context.guardrailDecisionIds());
    }

    private AiChatExecutor.Context withToolPolicy(
            AiChatExecutor.Context context, AiChatExecutor.ToolPolicy policy) {
        return new AiChatExecutor.Context(
                context.request(), context.history(), context.userMessage(), context.requester(),
                context.recorder(), policy != AiChatExecutor.ToolPolicy.NONE,
                context.streamVisibleContent(), policy, context.agentDepth(),
                context.approvalScope(), context.approvalWaitLifecycle(),
                context.agentId(), context.executionPurpose(), context.guardrailDecisionIds());
    }

    private boolean containsMutationWorkerResult(List<WorkflowResult> results) {
        return results != null && results.stream().anyMatch(this::containsMutationWorkerResult);
    }

    private boolean containsMutationWorkerResult(WorkflowResult result) {
        return result != null && result.successful()
                && (Boolean.TRUE.equals(result.metadata().get("mutation_worker"))
                || result.children().stream().anyMatch(this::containsMutationWorkerResult));
    }

    private String upstreamReferenceText(List<WorkflowResult> results) {
        if (results == null || results.isEmpty()) return null;
        StringBuilder evidence = new StringBuilder();
        for (WorkflowResult result : results) {
            evidence.append("\nNODE ").append(result.workflowId())
                    .append(" status=").append(result.successful() ? "completed" : "failed")
                    .append('\n');
            if (result.successful()) evidence.append(boundedReplanText(result.output())).append('\n');
        }
        return instructions.render(AiWorkflowInstructions.Template.UPSTREAM_RESULTS,
                Map.of("results", evidence.toString().strip())).value();
    }

    /**
     * Worker and chain outputs are model-generated, so they must never gain
     * system-role authority when re-entering a model call.
     */
    private static Message untrustedReference(String content) {
        return new UserMessage(content);
    }

    private String composedSynthesisPrompt(
            AiWorkflowNode node, List<WorkflowResult> results) {
        StringBuilder evidence = new StringBuilder();
        for (WorkflowResult result : results) {
            evidence.append("\nCHILD ").append(result.workflowId())
                    .append(" status=").append(result.successful() ? "completed" : "failed")
                    .append('\n');
            if (result.successful()) evidence.append(boundedReplanText(result.output())).append('\n');
        }
        return instructions.render(AiWorkflowInstructions.Template.COMPOSED_SYNTHESIS, Map.of(
                "workflowNode", node.id(),
                "workflow", node.workflow(),
                "results", evidence.toString().strip())).value();
    }

    private AiChatExecutor.Result executeDirectToolWorkflow(AiChatExecutor.Context context, AiWorkflowPlan plan) {
        if (workflowPlanner == null) {
            AiChatExecutor.Result result = executeDirectToolWorkflow(context, context.history());
            return withPlanMetadata(result, plan);
        }
        AiChatExecutor.Context attempt = directToolContext(context, context.history());
        AiChatExecutor.Result result;
        try {
            result = executeGroundedModel(
                    attempt, true, workerRecovery(attempt.toolPolicy()),
                    "The direct workflow completed no successful connectCenter domain tool call.");
        } catch (RequiredDomainToolCallException failure) {
            context.recorder().lifecycle("required_tool_unfulfilled",
                    "The assistant could not complete the required connectCenter tool call.",
                    Map.of("status", "failed", "recovery_attempts", 1));
            throw failure;
        }
        return withPlanMetadata(result, plan);
    }

    private AiChatExecutor.Result executeDirectToolWorkflow(AiChatExecutor.Context context,
                                                       List<Message> history) {
        return chatExecutor.execute(directToolContext(context, history));
    }

    private AiChatExecutor.Context directToolContext(AiChatExecutor.Context context, List<Message> history) {
        AiChatExecutor.ToolPolicy policy = context.toolPolicy();
        return new AiChatExecutor.Context(
                context.request().withMultiAgent(AiMultiAgentOptions.single()), history,
                context.userMessage(), context.requester(), context.recorder(),
                policy != AiChatExecutor.ToolPolicy.NONE, false, policy, 0,
                context.approvalScope(), context.approvalWaitLifecycle(),
                context.agentId(), context.executionPurpose(), context.guardrailDecisionIds());
    }

    private AiChatExecutor.Result withPlanMetadata(AiChatExecutor.Result result, AiWorkflowPlan plan) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.traceMetadata());
        metadata.put("workflow", plan.workflow());
        metadata.put("active_verb", plan.activeVerb());
        metadata.put("completed_verb", plan.completedVerb());
        return new AiChatExecutor.Result(result.answer(), metadata);
    }

    private AiChatExecutor.Result executeDelegatedWorkflow(
            AiChatExecutor.Context context, AiWorkflowPlan plan, int workflowIteration) {
        String fanoutId = delegatedFanoutId(
                context.request().requestId(), workflowIteration);
        String leadNodeId = fanoutId + "-lead";
        String executionKind = executionKind(context, plan);
        long resultTokenLimit = specialistResultTokenLimit(context, plan.tasks().size());
        List<WorkerControl> controls = createControls(
                context, plan, fanoutId, leadNodeId, executionKind);
        Map<String, Object> leadNamespace = leadNamespace(
                fanoutId, leadNodeId, plan, context.request().multiAgent(), executionKind,
                plan.tasks().size());
        AiTrajectoryRecorder leadRecorder = Objects.requireNonNull(
                context.recorder().fork(leadNamespace),
                "The delegated workflow lead recorder is required.");
        leadRecorder.lifecycle(leadLifecycle(executionKind, "started"), plan.guideMessage(), lifecycleMetadata(
                leadNamespace, plan.activeVerb(), plan.completedVerb(), "started", Map.of(
                        "agent_count", plan.tasks().size())));
        controls.forEach(this::recordPlannedWorker);

        long deadlineNanos = deadlineNanos(executionTimeout());
        List<AiMultiAgentWorkerResult> results;
        try {
            boolean parallel = controls.size() > 1
                    && (WorkflowTypes.PARALLEL.equals(plan.workflow())
                    || WorkflowTypes.ORCHESTRATOR_WORKERS.equals(plan.workflow()));
            results = parallel
                    ? executeParallel(context, controls, deadlineNanos, resultTokenLimit)
                    : executeChain(context, controls, deadlineNanos, resultTokenLimit);
            if (requestStopping(context.request().requestId())
                    || controls.stream().anyMatch(this::cancelled)) {
                throw new CancellationException("The delegated workflow was cancelled.");
            }
        } catch (CancellationException failure) {
            cancelOutstanding(controls, "cancelled");
            leadRecorder.terminalLifecycle(leadLifecycle(executionKind, "cancelled"),
                    "The delegated workflow was cancelled.",
                    lifecycleMetadata(leadNamespace, plan.activeVerb(),
                            plan.completedVerb(), "cancelled", Map.of()));
            recordFanOutUsage(context, fanoutId, executionKind, leadRecorder, controls);
            throw failure;
        } catch (RuntimeException failure) {
            failOutstanding(controls, "workflow_failed");
            leadRecorder.terminalLifecycle(leadLifecycle(executionKind, "failed"),
                    "The delegated workflow failed.",
                    lifecycleMetadata(leadNamespace, plan.activeVerb(), plan.completedVerb(), "failed",
                            Map.of("reason", failure.getClass().getSimpleName())));
            recordFanOutUsage(context, fanoutId, executionKind, leadRecorder, controls);
            throw failure;
        }

        interruptFence(leadRecorder, context.request().requestId(), "before_synthesis", leadNamespace, plan);
        List<AiMultiAgentWorkerResult> completed = results.stream()
                .filter(AiMultiAgentWorkerResult::successful).toList();
        if (completed.isEmpty()) {
            leadRecorder.terminalLifecycle(leadLifecycle(executionKind, "failed"),
                    "parallel".equals(executionKind)
                            ? "All parallel tasks failed." : "All delegated agents failed.",
                    lifecycleMetadata(leadNamespace, plan.activeVerb(), plan.completedVerb(), "failed",
                            Map.of("failed_agents", results.size())));
            recordFanOutUsage(context, fanoutId, executionKind, leadRecorder, controls);
            throw new IllegalStateException("All delegated agents failed.");
        }

        String synthesisGuide = StringUtils.hasText(plan.synthesisGuideMessage())
                ? plan.synthesisGuideMessage() : plan.guideMessage();
        if (StringUtils.hasText(synthesisGuide)) {
            leadRecorder.guide(synthesisGuide, lifecycleMetadata(leadNamespace,
                    plan.synthesisActiveVerb(), plan.synthesisCompletedVerb(), "synthesizing", Map.of()));
        }
        leadRecorder.lifecycle(leadLifecycle(executionKind, "synthesizing"), synthesisGuide,
                lifecycleMetadata(leadNamespace, plan.synthesisActiveVerb(),
                        plan.synthesisCompletedVerb(), "synthesizing", Map.of(
                                "completed_agents", completed.size(),
                                "failed_agents", results.size() - completed.size())));
        List<Message> synthesisHistory = new ArrayList<>(context.history());
        synthesisHistory.add(untrustedReference(synthesisPrompt(plan, results)));
        verifySynthesisBudget(context, synthesisHistory);
        interruptFence(leadRecorder, context.request().requestId(), "before_synthesis_call",
                leadNamespace, plan);
        boolean mutationAssignedToWorker = plan.tasks().stream()
                .anyMatch(task -> task.toolAccess() == AiWorkflowPlan.ToolAccess.FULL);
        AiChatExecutor.ToolPolicy synthesisPolicy = synthesisPolicy(
                context, plan.toolsNeeded(), mutationAssignedToWorker);
        try {
            AiChatExecutor.Result answer = chatExecutor.execute(new AiChatExecutor.Context(
                    context.request().withMultiAgent(AiMultiAgentOptions.single()), synthesisHistory,
                    context.userMessage(), context.requester(), leadRecorder,
                    synthesisPolicy != AiChatExecutor.ToolPolicy.NONE, false,
                    synthesisPolicy, 0,
                    context.approvalScope())
                    .withAgentIdentity(context.agentId(), context.executionPurpose())
                    .withGuardrailDecisions(context.guardrailDecisionIds()));
            interruptFence(leadRecorder, context.request().requestId(), "after_synthesis_call",
                    leadNamespace, plan);
            Map<String, Object> finalTrace = new LinkedHashMap<>(answer.traceMetadata());
            finalTrace.putAll(lifecycleMetadata(leadNamespace,
                    plan.synthesisActiveVerb(), plan.synthesisCompletedVerb(), "completed", Map.of(
                            "completed_agents", completed.size(),
                            "failed_agents", results.size() - completed.size())));
            finalTrace = Map.copyOf(finalTrace);
            leadRecorder.terminalLifecycle(leadLifecycle(executionKind, "completed"),
                    sentence(plan.synthesisCompletedVerb()), finalTrace);
            recordFanOutUsage(context, fanoutId, executionKind, leadRecorder, controls);
            return new AiChatExecutor.Result(answer.answer(), finalTrace);
        } catch (CancellationException failure) {
            cancelOutstanding(controls, "cancelled");
            leadRecorder.terminalLifecycle(leadLifecycle(executionKind, "cancelled"),
                    "Lead synthesis was cancelled.",
                    lifecycleMetadata(leadNamespace, plan.synthesisActiveVerb(),
                            plan.synthesisCompletedVerb(), "cancelled", Map.of()));
            recordFanOutUsage(context, fanoutId, executionKind, leadRecorder, controls);
            throw failure;
        } catch (RuntimeException failure) {
            leadRecorder.terminalLifecycle(leadLifecycle(executionKind, "failed"),
                    "Lead synthesis failed.",
                    lifecycleMetadata(leadNamespace, plan.synthesisActiveVerb(),
                            plan.synthesisCompletedVerb(), "failed", Map.of(
                                    "reason", "synthesis_failed")));
            recordFanOutUsage(context, fanoutId, executionKind, leadRecorder, controls);
            throw failure;
        }
    }

    private List<WorkerControl> createControls(AiChatExecutor.Context context, AiWorkflowPlan plan,
                                                String fanoutId, String leadNodeId,
                                                String executionKind) {
        List<WorkerControl> controls = new ArrayList<>();
        for (int index = 0; index < plan.tasks().size(); index++) {
            int ordinal = index + 1;
            AiWorkflowPlan.Task task = plan.tasks().get(index);
            AiAgentDefinition definition = definition(task);
            Map<String, Object> namespace = specialistNamespace(
                    fanoutId, leadNodeId, plan.workflow(), executionKind,
                    definition, task, ordinal);
            AiTrajectoryRecorder recorder = Objects.requireNonNull("parallel".equals(executionKind)
                    ? context.recorder().forkParallelExecution(
                            definition.id(), task.instruction(), namespace)
                    : context.recorder().forkSubagent(
                            definition.id(), task.instruction(), namespace),
                    "The delegated workflow worker recorder is required.");
            controls.add(new WorkerControl(ordinal, task, definition, executionKind,
                    recorder.conversationId(), recorder, namespace,
                    new AtomicReference<>(), new AtomicBoolean(), new AtomicReference<>()));
        }
        return controls;
    }

    private List<AiMultiAgentWorkerResult> executeParallel(
            AiChatExecutor.Context context, List<WorkerControl> controls,
            long deadlineNanos, long tokenLimit) {
        Map<String, AiMutationApprovalScope> approvalScopes = parallelApprovalScopes(context, controls);
        List<Future<AiMultiAgentWorkerResult>> futures = new ArrayList<>();
        try {
            for (WorkerControl control : controls) {
                futures.add(executor().submit(() -> executeWorker(
                        context, control, deadlineNanos, tokenLimit, List.of(),
                        approvalScopes.get(participantId(control)))));
            }
        } catch (RuntimeException scheduling) {
            futures.forEach(future -> future.cancel(true));
            throw new IllegalStateException("Delegated agents could not be scheduled.", scheduling);
        }
        List<AiMultiAgentWorkerResult> results = new ArrayList<>();
        for (int index = 0; index < futures.size(); index++) {
            WorkerControl control = controls.get(index);
            long remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0) {
                if (futures.get(index).isDone()) {
                    try {
                        results.add(futures.get(index).get());
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        futures.forEach(future -> future.cancel(true));
                        throw new CancellationException("Delegated workflow was interrupted.");
                    } catch (ExecutionException | CancellationException failure) {
                        settleWorkerFutureFailure(control, failure);
                        results.add(failed(control));
                    }
                } else {
                    futures.get(index).cancel(true);
                    markFailed(control, "timeout");
                    results.add(failed(control));
                }
                continue;
            }
            try {
                results.add(futures.get(index).get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException failure) {
                futures.get(index).cancel(true);
                markFailed(control, "timeout");
                results.add(failed(control));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                futures.forEach(future -> future.cancel(true));
                throw new CancellationException("Delegated workflow was interrupted.");
            } catch (ExecutionException | CancellationException failure) {
                settleWorkerFutureFailure(control, failure);
                results.add(failed(control));
            }
        }
        return results;
    }

    private List<AiMultiAgentWorkerResult> executeChain(
            AiChatExecutor.Context context, List<WorkerControl> controls,
            long deadlineNanos, long tokenLimit) {
        List<AiMultiAgentWorkerResult> results = new ArrayList<>();
        for (WorkerControl control : controls) {
            if (remainingNanos(deadlineNanos) <= 0) {
                markFailed(control, "timeout");
                results.add(failed(control));
                continue;
            }
            results.add(executeWorker(context, control, deadlineNanos, tokenLimit, results,
                    individualApprovalScope(context, control)));
            if (cancelled(control)) {
                throw new CancellationException("The delegated workflow was cancelled.");
            }
        }
        return results;
    }

    private AiMultiAgentWorkerResult executeWorker(AiChatExecutor.Context parent, WorkerControl control,
                                       long deadlineNanos, long tokenLimit,
                                       List<AiMultiAgentWorkerResult> preceding,
                                       AiMutationApprovalScope approvalScope) {
        start(control);
        try (SpecialistAdmissionLease admission = new SpecialistAdmissionLease(
                parent.requester(), parent.request().requestId(), deadlineNanos)) {
            if (!admission.tryAcquire()) {
                markFailed(control, "admission_timeout");
                return failed(control);
            }
            if (requestStopping(parent.request().requestId())) {
                markCancelled(control, "cancelled");
                return failed(control);
            }
            AiChatExecutor.ToolPolicy policy = workerPolicy(
                    control.task(), parent.toolsEnabled(), parent);
            List<Message> history = new ArrayList<>();
            history.add(new SystemMessage(workerPrompt(control, policy)));
            history.add(originalRequestReference(parent.userMessage()));
            String chainReference = chainReferenceText(preceding);
            if (chainReference != null) {
                history.add(untrustedReference(chainReference));
            }
            AiChatExecutor.Context child = new AiChatExecutor.Context(
                    parent.request().withConversationId(control.childConversationId())
                            .withMultiAgent(AiMultiAgentOptions.single()),
                    history, workerAssignmentMessage(parent.userMessage(), control.task()),
                    parent.requester(), control.recorder(),
                    policy != AiChatExecutor.ToolPolicy.NONE, false, policy, 1,
                    approvalScope, admission)
                    .withAgentIdentity(control.definition().id(),
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.WORKER)
                    .withGuardrailDecisions(parent.guardrailDecisionIds());
            String answer = executeGroundedModel(
                    child, policy != AiChatExecutor.ToolPolicy.NONE, workerRecovery(policy),
                    "The delegated worker completed no successful connectCenter domain tool call.")
                    .answer();
            if (requestStopping(parent.request().requestId())) {
                markCancelled(control, "cancelled");
                return failed(control);
            }
            AiBoundedAnswer bounded = bounded(answer, tokenLimit);
            AiMultiAgentWorkerResult result = completed(control, bounded.value());
            control.result().set(result);
            markCompleted(control, bounded);
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            markCancelled(control, "interrupted");
            return failed(control);
        } catch (CancellationException failure) {
            markCancelled(control, "cancelled");
            return failed(control);
        } catch (RuntimeException failure) {
            LOGGER.warn("AI worker {} failed for request {}", control.definition().id(),
                    parent.request().requestId(), failure);
            markFailed(control, "execution_failure");
            return failed(control);
        } finally {
            if (approvalCoordinator != null) {
                approvalCoordinator.participantFinished(approvalScope);
            }
        }
    }

    private void settleWorkerFutureFailure(WorkerControl control, Throwable failure) {
        Throwable cause = failure instanceof ExecutionException execution
                ? execution.getCause() : failure;
        if (cause instanceof CancellationException) {
            markCancelled(control, "cancelled");
        } else {
            markFailed(control, "execution_failure");
        }
    }

    private Map<String, AiMutationApprovalScope> parallelApprovalScopes(
            AiChatExecutor.Context context, List<WorkerControl> controls) {
        if (approvalCoordinator == null) {
            Map<String, AiMutationApprovalScope> scopes = new LinkedHashMap<>();
            controls.forEach(control -> scopes.put(participantId(control),
                    individualApprovalScope(context, control)));
            return Map.copyOf(scopes);
        }
        Map<String, AiMutationApprovalCoordinator.Participant> participants = new LinkedHashMap<>();
        controls.forEach(control -> participants.put(participantId(control),
                new AiMutationApprovalCoordinator.Participant(
                        control.definition().id(), control.task().label())));
        return approvalCoordinator.openParallelGroup(
                rootConversationId(context), context.request().requestId(), participants);
    }

    private ParallelizationWorkflow.BranchLifecycle parallelApprovalLifecycle() {
        if (approvalCoordinator == null) {
            return ParallelizationWorkflow.BranchLifecycle.NOOP;
        }
        return new ParallelizationWorkflow.BranchLifecycle() {
            @Override
            public List<WorkflowContext> open(WorkflowContext parent, List<String> branchIds) {
                AiChatExecutor.Context parentExecution = legacyContext(parent);
                Map<String, AiMutationApprovalCoordinator.Participant> participants =
                        new LinkedHashMap<>();
                branchIds.forEach(branchId -> participants.put(branchId,
                        new AiMutationApprovalCoordinator.Participant(branchId, branchId)));
                Map<String, AiMutationApprovalScope> scopes = approvalCoordinator.openParallelGroup(
                        rootConversationId(parentExecution),
                        parentExecution.request().requestId(), participants);
                return branchIds.stream().map(branchId -> new WorkflowContext(
                        workflowInvocation(parentExecution.withApprovalScope(scopes.get(branchId))),
                        parent.upstreamResults(), true)).toList();
            }

            @Override
            public void finished(WorkflowContext branch) {
                approvalCoordinator.participantFinished(
                        legacyContext(branch).approvalScope());
            }
        };
    }

    private WorkflowInvocation workflowInvocation(AiChatExecutor.Context context) {
        String requesterId = context.requester() != null && context.requester().userId() != null
                ? context.requester().userId().value().toString()
                : context.requester() != null && StringUtils.hasText(context.requester().username())
                ? context.requester().username() : "unknown";
        ExecutionScope scope = new ExecutionScope(context.request().requestId(),
                context.request().conversationId(), requesterId, 0L,
                context.executionPurpose(), context.guardrailDecisionIds());
        return new WorkflowInvocation(scope,
                context.history().stream().map(this::coreMessage).toList(),
                new AiMessage.User(Objects.requireNonNullElse(
                        context.userMessage().getText(), "")),
                new LegacyAgentRunner(context));
    }

    private AiChatExecutor.Context legacyContext(WorkflowContext context) {
        if (context.invocation().agentRunner() instanceof LegacyAgentRunner runner) {
            return runner.context;
        }
        throw new IllegalArgumentException(
                "This Workflow invocation was not created by the chat application adapter.");
    }

    private AiMessage coreMessage(Message message) {
        if (message instanceof SystemMessage system) {
            return new AiMessage.System(Objects.requireNonNullElse(system.getText(), ""));
        }
        if (message instanceof UserMessage user) {
            return new AiMessage.User(Objects.requireNonNullElse(user.getText(), ""));
        }
        if (message instanceof AssistantMessage assistant) {
            return new AiMessage.Assistant(Objects.requireNonNullElse(assistant.getText(), ""));
        }
        return new AiMessage.ToolResult("workflow-history", "workflow-tool",
                Objects.requireNonNullElse(message != null ? message.getText() : null, ""));
    }

    private final class LegacyAgentRunner implements WorkflowInvocation.AgentRunner {
        private final AiChatExecutor.Context context;

        private LegacyAgentRunner(AiChatExecutor.Context context) {
            this.context = context;
        }

        @Override
        public AgentRunResult run(AgentInvocation invocation) {
            return chatExecutor.executeAgent(invocation);
        }
    }

    private AiMutationApprovalScope composedApprovalScope(
            AiChatExecutor.Context context, AiWorkflowNode node,
            AiAgentDefinition definition, AiWorkflowPlan.Task task,
            String childConversationId) {
        AiMutationApprovalScope inherited = context.approvalScope();
        if (inherited != null && inherited.parallel()) {
            return new AiMutationApprovalScope(inherited.rootConversationId(),
                    inherited.parallelGroupId(), inherited.participantId(),
                    definition.id(), task.label());
        }
        return AiMutationApprovalScope.individual(rootConversationId(context),
                childConversationId + ":" + node.id(), definition.id(), task.label());
    }

    private Duration executionTimeout() {
        if (approvalCoordinator == null
                || approvalCoordinator.decisionTimeout().compareTo(specialistTimeout) <= 0) {
            return specialistTimeout;
        }
        return approvalCoordinator.decisionTimeout();
    }

    private AiMutationApprovalScope individualApprovalScope(
            AiChatExecutor.Context context, WorkerControl control) {
        return AiMutationApprovalScope.individual(rootConversationId(context),
                participantId(control), control.definition().id(), control.task().label());
    }

    private String rootConversationId(AiChatExecutor.Context context) {
        return context.approvalScope() != null
                ? context.approvalScope().rootConversationId()
                : context.request().conversationId();
    }

    private String participantId(WorkerControl control) {
        return control.childConversationId();
    }

    private String workerPrompt(WorkerControl control, AiChatExecutor.ToolPolicy policy) {
        return workerInstruction(control.definition(), control.task(), policy);
    }

    private String workerInstruction(
            AiAgentDefinition definition, AiWorkflowPlan.Task task,
            AiChatExecutor.ToolPolicy policy) {
        AiWorkflowInstructions.Template template = policy == AiChatExecutor.ToolPolicy.FULL
                ? AiWorkflowInstructions.Template.WORKER_FULL
                : AiWorkflowInstructions.Template.WORKER_RESTRICTED;
        return instructions.render(template, Map.of(
                "agentInstruction", definition.instruction(),
                "assignment", task.instruction())).value();
    }

    private AiChatExecutor.ToolPolicy workerPolicy(
            AiWorkflowPlan.Task task, boolean toolsNeeded, AiChatExecutor.Context parent) {
        AiWorkflowPlan.ToolAccess access = task.effectiveToolAccess(
                toolsNeeded && parent.toolsEnabled());
        if (access == AiWorkflowPlan.ToolAccess.NONE
                || parent.toolPolicy() == AiChatExecutor.ToolPolicy.NONE) {
            return AiChatExecutor.ToolPolicy.NONE;
        }
        if (access == AiWorkflowPlan.ToolAccess.FULL
                && parent.toolPolicy() == AiChatExecutor.ToolPolicy.FULL) {
            return AiChatExecutor.ToolPolicy.FULL;
        }
        return AiChatExecutor.ToolPolicy.READ_ONLY;
    }

    private AiChatExecutor.ToolPolicy synthesisPolicy(
            AiChatExecutor.Context context, boolean toolsNeeded,
            boolean mutationAssignedToWorker) {
        if (!toolsNeeded || context.toolPolicy() == AiChatExecutor.ToolPolicy.NONE) {
            return AiChatExecutor.ToolPolicy.NONE;
        }
        if (mutationAssignedToWorker
                || context.toolPolicy() == AiChatExecutor.ToolPolicy.READ_ONLY) {
            return AiChatExecutor.ToolPolicy.READ_ONLY;
        }
        return AiChatExecutor.ToolPolicy.FULL;
    }

    private boolean containsMutationWorker(AiWorkflowNode node) {
        if (node.task() != null
                && node.task().toolAccess() == AiWorkflowPlan.ToolAccess.FULL) {
            return true;
        }
        return node.children().stream().anyMatch(this::containsMutationWorker)
                || node.routes().values().stream().anyMatch(this::containsMutationWorker);
    }

    private String workerRecovery(AiChatExecutor.ToolPolicy policy) {
        AiWorkflowInstructions.Template template = policy == AiChatExecutor.ToolPolicy.FULL
                ? AiWorkflowInstructions.Template.REQUIRED_TOOL_RECOVERY
                : AiWorkflowInstructions.Template.READ_ONLY_TOOL_RECOVERY;
        return instructions.render(template).value();
    }

    private AiChatExecutor.Result executeGroundedModel(
            AiChatExecutor.Context attempt, boolean domainToolRequired,
            String recoveryInstruction, String failureMessage) {
        if (!domainToolRequired) {
            return chatExecutor.execute(attempt);
        }
        long successfulBefore = attempt.recorder().successfulDomainToolCallCount();
        AiChatExecutor.Result result = chatExecutor.execute(attempt);
        if (hasNewSuccessfulDomainToolCall(attempt, successfulBefore)
                || approvalBarrierResolved(result)) {
            return result;
        }

        List<Message> recoveryHistory = new ArrayList<>(attempt.history());
        recoveryHistory.add(new AssistantMessage(result.answer()));
        recoveryHistory.add(new SystemMessage(recoveryInstruction));
        AiChatExecutor.Context recovery = new AiChatExecutor.Context(
                attempt.request(), recoveryHistory, attempt.userMessage(), attempt.requester(),
                attempt.recorder(), attempt.toolsEnabled(), false,
                attempt.toolPolicy(), attempt.agentDepth(), attempt.approvalScope(),
                attempt.approvalWaitLifecycle())
                .withAgentIdentity(attempt.agentId(), attempt.executionPurpose())
                .withGuardrailDecisions(attempt.guardrailDecisionIds());
        result = chatExecutor.execute(recovery);
        if (!hasNewSuccessfulDomainToolCall(recovery, successfulBefore)) {
            throw new RequiredDomainToolCallException(failureMessage);
        }
        return result;
    }

    private boolean hasNewSuccessfulDomainToolCall(AiChatExecutor.Context context, long baseline) {
        return context.recorder().successfulDomainToolCallCount() > baseline;
    }

    private boolean approvalBarrierResolved(AiChatExecutor.Result result) {
        return Boolean.TRUE.equals(result.traceMetadata().get("approvalBarrierResolved"));
    }

    private Message originalRequestReference(UserMessage original) {
        return untrustedReference(instructions.render(
                AiWorkflowInstructions.Template.ORIGINAL_REQUEST_REFERENCE,
                Map.of("originalRequest",
                        Objects.requireNonNullElse(original.getText(), ""))).value());
    }

    private UserMessage workerAssignmentMessage(
            UserMessage original, AiWorkflowPlan.Task task) {
        return UserMessage.builder()
                .text(instructions.render(AiWorkflowInstructions.Template.WORKER_ASSIGNMENT,
                        Map.of("assignment", task.instruction())).value())
                .media(original.getMedia())
                .build();
    }

    private String chainReferenceText(List<AiMultiAgentWorkerResult> preceding) {
        List<AiMultiAgentWorkerResult> successful = preceding == null ? List.of()
                : preceding.stream().filter(AiMultiAgentWorkerResult::successful).toList();
        if (successful.isEmpty()) return null;
        StringBuilder evidence = new StringBuilder();
        successful.forEach(result -> evidence
                .append("- ").append(result.task().label()).append(": ")
                .append(result.answer()).append('\n'));
        return instructions.render(AiWorkflowInstructions.Template.UPSTREAM_RESULTS,
                Map.of("results", evidence.toString().strip())).value();
    }

    private String synthesisPrompt(
            AiWorkflowPlan plan, List<AiMultiAgentWorkerResult> results) {
        StringBuilder evidence = new StringBuilder();
        for (AiMultiAgentWorkerResult result : results) {
            evidence.append("\nWORKER ").append(result.ordinal()).append(" [")
                    .append(result.definition().id()).append(" / ").append(result.task().label())
                    .append("] status=").append(result.successful() ? "completed" : "failed").append('\n');
            if (result.successful()) evidence.append(result.answer()).append('\n');
        }
        return instructions.render(AiWorkflowInstructions.Template.FINAL_SYNTHESIS, Map.of(
                "workflow", plan.workflow(),
                "results", evidence.toString().strip())).value();
    }

    private void start(WorkerControl control) {
        synchronized (control) {
            if (control.started().compareAndSet(false, true)) {
                control.recorder().lifecycle(workerLifecycle(control, "started"),
                        workerStatus(control, false),
                        workerMetadata(control, "started", Map.of()));
            }
        }
    }

    private void recordPlannedWorker(WorkerControl control) {
        control.recorder().lifecycle(workerLifecycle(control, "planned"),
                sentence(control.task().label() + " is queued"),
                workerMetadata(control, "planned", Map.of()));
    }

    private boolean markCompleted(WorkerControl control, AiBoundedAnswer bounded) {
        synchronized (control) {
            if (!control.terminalStatus().compareAndSet(null, "completed")) return false;
            Map<String, Object> additional = new LinkedHashMap<>();
            if (bounded.truncated()) {
                additional.put("result_truncated", true);
                additional.put("original_utf8_bytes", bounded.originalUtf8Bytes());
                additional.put("returned_utf8_bytes", bounded.returnedUtf8Bytes());
            }
            control.recorder().terminalLifecycle(workerLifecycle(control, "completed"),
                    workerStatus(control, true),
                    workerMetadata(control, "completed", additional));
            return true;
        }
    }

    private boolean markFailed(WorkerControl control, String reason) {
        synchronized (control) {
            if (!control.terminalStatus().compareAndSet(null, "failed")) return false;
            control.recorder().terminalLifecycle(workerLifecycle(control, "failed"),
                    "Could not complete " + control.task().label() + ".",
                    workerMetadata(control, "failed", Map.of("reason", reason)));
            return true;
        }
    }

    private boolean markCancelled(WorkerControl control, String reason) {
        synchronized (control) {
            if (!control.terminalStatus().compareAndSet(null, "cancelled")) return false;
            control.recorder().terminalLifecycle(workerLifecycle(control, "cancelled"),
                    "Stopped " + control.task().label() + ".",
                    workerMetadata(control, "cancelled", Map.of("reason", reason)));
            return true;
        }
    }

    private String workerStatus(WorkerControl control, boolean completed) {
        String verb = completed ? control.task().completedVerb() : control.task().activeVerb();
        String guide = completed ? null : control.task().guideMessage();
        return sentence(StringUtils.hasText(guide) ? guide : verb + " " + control.task().label());
    }

    private Map<String, Object> workerMetadata(WorkerControl control, String status,
                                                Map<String, Object> additional) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", status);
        metadata.put("execution_kind", control.executionKind());
        metadata.put("conversation_kind",
                "parallel".equals(control.executionKind()) ? "PARALLEL" : "SUBAGENT");
        metadata.put("agent_id", control.definition().id());
        metadata.put("child_conversation_id", control.childConversationId());
        metadata.put("active_verb", control.task().activeVerb());
        metadata.put("completed_verb", control.task().completedVerb());
        metadata.putAll(additional);
        return Map.copyOf(metadata);
    }

    private void failOutstanding(Collection<WorkerControl> controls, String reason) {
        controls.forEach(control -> markFailed(control, reason));
    }

    private void cancelOutstanding(Collection<WorkerControl> controls, String reason) {
        controls.forEach(control -> markCancelled(control, reason));
    }

    private boolean cancelled(WorkerControl control) {
        return "cancelled".equals(control.terminalStatus().get());
    }

    private void recordFanOutUsage(AiChatExecutor.Context context, String fanoutId,
                                   String executionKind,
                                   AiTrajectoryRecorder leadRecorder, List<WorkerControl> controls) {
        try {
            List<AiUsageSnapshot> usage = new ArrayList<>();
            usage.add(leadRecorder.usageSnapshot());
            controls.forEach(control -> usage.add(control.recorder().usageSnapshot()));
            context.recorder().recordFanOutUsage(fanoutId, executionKind, usage);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not record AI workflow usage for request {}",
                    context.request().requestId(), failure);
        }
    }

    private AiAgentDefinition definition(AiWorkflowPlan.Task task) {
        if (agents != null) return agents.requireWorker(task.agentId());
        String id = StringUtils.hasText(task.agentId()) ? task.agentId() : "general-purpose";
        return new AiAgentDefinition(id, task.label(), "Compatibility worker",
                instructions.render(AiWorkflowInstructions.Template.COMPATIBILITY_WORKER,
                        Map.of("agentId", id)).value());
    }

    private AiWorkflowPlan fallbackPlan(AiChatExecutor.Context context) {
        AiMultiAgentOptions options = context.request().multiAgent();
        if (options == null || !options.active()) {
            return new AiWorkflowPlan(WorkflowTypes.DIRECT, true, "Processing the request.",
                    "Working", "Completed", null, "Synthesizing", "Synthesized", List.of());
        }
        int count = Math.max(1, options.maxAgents());
        List<AiWorkflowPlan.Task> tasks = new ArrayList<>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            tasks.add(new AiWorkflowPlan.Task("Worker " + ordinal, "general-purpose",
                    instructions.render(AiWorkflowInstructions.Template.FALLBACK_TASK).value(),
                    null, "Reviewing", "Reviewed"));
        }
        return new AiWorkflowPlan(WorkflowTypes.PARALLEL, true,
                "Specialist agents will review the request and the lead agent will synthesize their findings.",
                "Reviewing", "Reviewed",
                "The lead agent is synthesizing the specialist findings.",
                "Synthesizing", "Synthesized", tasks);
    }

    private long specialistResultTokenLimit(AiChatExecutor.Context context, int taskCount) {
        Optional<AiContextBudget> budget = contextBudgets != null
                ? contextBudgets.budget(context.request().modelName()) : Optional.empty();
        if (budget.isEmpty()) return MAX_SPECIALIST_RESULT_TOKENS;
        long estimatedBase = contextBudgets.estimateInputTokens(
                context.history(), context.userMessage(), context.request().pageContext());
        long available = budget.get().safeInputLimit() - estimatedBase - SYNTHESIS_RESERVE_TOKENS;
        long perWorker = available / Math.max(1, taskCount);
        if (perWorker < MIN_SPECIALIST_RESULT_TOKENS) {
            throw new IllegalArgumentException("Delegated workflow does not fit the selected model's context budget.");
        }
        return Math.min(MAX_SPECIALIST_RESULT_TOKENS, perWorker);
    }

    private void verifySynthesisBudget(AiChatExecutor.Context context, List<Message> history) {
        if (contextBudgets == null) return;
        contextBudgets.budget(context.request().modelName()).ifPresent(budget -> {
            long estimated = contextBudgets.estimateInputTokens(
                    history, context.userMessage(), context.request().pageContext());
            if (budget.exceedsSafeInput(estimated)) {
                throw new IllegalArgumentException("Workflow synthesis exceeds the selected model's context budget.");
            }
        });
    }

    private Map<String, Object> leadNamespace(
            String fanoutId, String nodeId, AiWorkflowPlan plan,
            AiMultiAgentOptions options, String executionKind, int maxAgents) {
        Map<String, Object> namespace = new LinkedHashMap<>(executionNamespace(
                fanoutId, nodeId, null, "lead", executionKind, plan.workflow(), 0));
        namespace.put("agent_name", "lead");
        namespace.put("agent_role", "workflow orchestrator");
        namespace.put("strategy", options != null ? options.strategy() : "model-selected");
        namespace.put("max_agents", maxAgents);
        namespace.put("active_verb", plan.activeVerb());
        namespace.put("completed_verb", plan.completedVerb());
        return Map.copyOf(namespace);
    }

    private Map<String, Object> specialistNamespace(String fanoutId, String parentNodeId,
                                                     String workflow, String executionKind,
                                                     AiAgentDefinition definition,
                                                     AiWorkflowPlan.Task task, int ordinal) {
        return workerNamespace(fanoutId,
                fanoutId + "-agent-" + String.format("%02d", ordinal),
                parentNodeId, workflow, executionKind, definition, task, ordinal);
    }

    private Map<String, Object> workerNamespace(
            String fanoutId, String nodeId, String parentNodeId, String workflow,
            String executionKind, AiAgentDefinition definition,
            AiWorkflowPlan.Task task, int ordinal) {
        Map<String, Object> namespace = new LinkedHashMap<>(executionNamespace(
                fanoutId, nodeId, parentNodeId, "worker", executionKind, workflow, 1));
        namespace.put("agent_id", definition.id());
        namespace.put("agent_name", definition.name());
        namespace.put("agent_role", definition.description());
        namespace.put("task_label", task.label());
        namespace.put("ordinal", ordinal);
        namespace.put("active_verb", task.activeVerb());
        namespace.put("completed_verb", task.completedVerb());
        return Map.copyOf(namespace);
    }

    private Map<String, Object> executionNamespace(
            String fanoutId, String nodeId, String parentNodeId, String executionScope,
            String executionKind, String workflow, int depth) {
        Map<String, Object> namespace = new LinkedHashMap<>();
        namespace.put("fanout_id", fanoutId);
        namespace.put("node_id", nodeId);
        if (StringUtils.hasText(parentNodeId)) {
            namespace.put("parent_node_id", parentNodeId);
        }
        namespace.put("execution_scope", executionScope);
        namespace.put("execution_kind", executionKind);
        namespace.put("workflow", workflow);
        namespace.put("depth", depth);
        return namespace;
    }

    private Map<String, Object> lifecycleMetadata(Map<String, Object> namespace, String activeVerb,
                                                   String completedVerb, String status,
                                                   Map<String, Object> additional) {
        Map<String, Object> result = new LinkedHashMap<>(namespace);
        result.put("status", status);
        result.put("active_verb", activeVerb);
        result.put("completed_verb", completedVerb);
        result.putAll(additional);
        return Map.copyOf(result);
    }

    private void interruptFence(AiTrajectoryRecorder recorder, String requestId, String stage,
                                Map<String, Object> namespace, AiWorkflowPlan plan) {
        if (!Thread.currentThread().isInterrupted() && !requestStopping(requestId)) return;
        recorder.terminalLifecycle(leadLifecycle(
                        Objects.toString(namespace.get("execution_kind"), "multi_agent"), "cancelled"),
                "The delegated workflow was interrupted.",
                lifecycleMetadata(namespace, plan.activeVerb(), plan.completedVerb(), "cancelled",
                        Map.of("reason", "interrupted", "stage", stage)));
        throw new CancellationException("Delegated workflow was interrupted at " + stage + ".");
    }

    private void cancellationFence(String requestId, String stage) {
        if (!Thread.currentThread().isInterrupted() && !requestStopping(requestId)) return;
        throw new CancellationException("Workflow was interrupted at " + stage + ".");
    }

    private boolean requestStopping(String requestId) {
        return requests != null && requests.shouldDiscardResult(requestId);
    }

    private Semaphore userAdmission(ScoreUser requester) {
        String key = requester != null && requester.userId() != null
                ? Objects.toString(requester.userId().value(), "anonymous") : "anonymous";
        return userAdmission.computeIfAbsent(key,
                ignored -> new Semaphore(maxConcurrentSpecialistsPerUser, true));
    }

    private String fanoutId(String requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    Objects.toString(requestId, "missing-request-id").getBytes(StandardCharsets.UTF_8));
            return "fanout-" + HexFormat.of().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private String delegatedFanoutId(String requestId, int workflowIteration) {
        String base = fanoutId(requestId);
        return workflowIteration > 0 ? base + "-iteration-" + workflowIteration : base;
    }

    private String executionKind(AiChatExecutor.Context context, AiWorkflowPlan plan) {
        AiMultiAgentOptions options = context.request().multiAgent();
        return WorkflowTypes.PARALLEL.equals(plan.workflow()) && (options == null || !options.active())
                ? "parallel" : "multi_agent";
    }

    private String leadLifecycle(String executionKind, String phase) {
        return "parallel".equals(executionKind)
                ? "parallel_workflow_" + phase : "multi_agent_" + phase;
    }

    private String workerLifecycle(WorkerControl control, String phase) {
        return "parallel".equals(control.executionKind())
                ? "parallel_task_" + phase : "subagent_" + phase;
    }

    private AiBoundedAnswer bounded(String answer, long tokenLimit) {
        String value = Objects.requireNonNullElse(answer, "");
        long byteLimit = Math.max(1L, Math.min(MAX_SPECIALIST_RESULT_TOKENS, tokenLimit)) * 3L;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= byteLimit) {
            return new AiBoundedAnswer(value, false, bytes.length, bytes.length);
        }
        String truncationSuffix = "\n" + instructions.render(
                AiWorkflowInstructions.Template.RESULT_TRUNCATED).value();
        int suffixBytes = truncationSuffix.getBytes(StandardCharsets.UTF_8).length;
        int prefixLimit = (int) Math.max(0L, byteLimit - suffixBytes);
        int chars = 0;
        int usedBytes = 0;
        while (chars < value.length()) {
            int codePoint = value.codePointAt(chars);
            int encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + encoded > prefixLimit) break;
            chars += Character.charCount(codePoint);
            usedBytes += encoded;
        }
        String truncated = value.substring(0, chars) + truncationSuffix;
        return new AiBoundedAnswer(truncated, true, bytes.length,
                truncated.getBytes(StandardCharsets.UTF_8).length);
    }

    private long deadlineNanos(Duration timeout) {
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            timeoutNanos = Long.MAX_VALUE / 2L;
        }
        long now = System.nanoTime();
        return timeoutNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private String sentence(String value) {
        if (!StringUtils.hasText(value)) return "Completed.";
        String stripped = value.strip();
        return stripped.matches(".*[.!?。！？]$") ? stripped : stripped + ".";
    }

    private ExecutorService executor() {
        ExecutorService current = executor;
        if (current != null) return current;
        synchronized (this) {
            if (closed) throw new RejectedExecutionException("AiWorkflowExecutionCoordinator is closed.");
            if (executor == null) executor = Executors.newVirtualThreadPerTaskExecutor();
            return executor;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        ExecutorService current;
        synchronized (this) {
            closed = true;
            current = executor;
            executor = null;
        }
        if (current == null) return;
        current.shutdownNow();
        try {
            if (!current.awaitTermination(CLOSE_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                LOGGER.warn("The AI workflow executor did not terminate within {}s.", CLOSE_GRACE.toSeconds());
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private AiMultiAgentWorkerResult completed(WorkerControl control, String answer) {
        return new AiMultiAgentWorkerResult(
                control.ordinal(), control.task(), control.definition(), true, answer);
    }

    private AiMultiAgentWorkerResult failed(WorkerControl control) {
        return new AiMultiAgentWorkerResult(
                control.ordinal(), control.task(), control.definition(), false, "");
    }

    /**
     * Owns one worker's global and per-user permits. Approval suspension releases
     * both permits so every sibling can reach the same barrier; resumption
     * reacquires them before any approved callback or provider continuation runs.
     */
    private final class SpecialistAdmissionLease
            implements AiChatExecutor.ApprovalWaitLifecycle, AutoCloseable {

        private final Semaphore userSlot;
        private final String requestId;
        private final long deadlineNanos;
        private boolean held;
        private boolean closed;

        private SpecialistAdmissionLease(
                ScoreUser requester, String requestId, long deadlineNanos) {
            this.userSlot = userAdmission(requester);
            this.requestId = requestId;
            this.deadlineNanos = deadlineNanos;
        }

        private synchronized boolean tryAcquire() throws InterruptedException {
            if (held) {
                return true;
            }
            if (closed || requestStopping(requestId)) {
                return false;
            }
            long remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0 || !userSlot.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                return false;
            }
            boolean globalAcquired = false;
            try {
                remaining = remainingNanos(deadlineNanos);
                globalAcquired = remaining > 0 && specialistAdmission.tryAcquire(
                        remaining, TimeUnit.NANOSECONDS);
                if (!globalAcquired) {
                    return false;
                }
                held = true;
                return true;
            } finally {
                if (!globalAcquired) {
                    userSlot.release();
                }
            }
        }

        @Override
        public synchronized void suspendForApproval() {
            releaseHeld();
        }

        @Override
        public void resumeAfterApproval() {
            try {
                if (!tryAcquire()) {
                    throw new CancellationException(
                            "Workflow worker could not resume after approval.");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CancellationException(
                        "Workflow worker was interrupted while resuming after approval.");
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            releaseHeld();
        }

        private void releaseHeld() {
            if (!held) {
                return;
            }
            held = false;
            specialistAdmission.release();
            userSlot.release();
        }
    }

    private static final class RequiredDomainToolCallException extends IllegalStateException {
        private RequiredDomainToolCallException(String message) {
            super(message);
        }
    }

    private record ComposedExecution(
            String fanoutId,
            String leadNodeId,
            Map<String, WorkerControl> controls) {
    }

    private record PlannedComposedNode(
            AiWorkflowNode node,
            boolean registeredWorker) {
    }

    private record PreparedComposedControl(
            AiWorkflowNode node,
            AiWorkflowPlan.Task task,
            AiAgentDefinition definition,
            boolean registeredWorker,
            int ordinal) {
    }

    private static final class WorkerControl {
        private final int ordinal;
        private final AiWorkflowPlan.Task task;
        private final AiAgentDefinition definition;
        private final String executionKind;
        private final String childConversationId;
        private final AiTrajectoryRecorder recorder;
        private final Map<String, Object> namespace;
        private final AtomicReference<String> terminalStatus;
        private final AtomicBoolean started;
        private final AtomicReference<AiMultiAgentWorkerResult> result;

        private WorkerControl(
                int ordinal, AiWorkflowPlan.Task task, AiAgentDefinition definition,
                String executionKind, String childConversationId,
                AiTrajectoryRecorder recorder, Map<String, Object> namespace,
                AtomicReference<String> terminalStatus,
                AtomicBoolean started, AtomicReference<AiMultiAgentWorkerResult> result) {
            this.ordinal = ordinal;
            this.task = task;
            this.definition = definition;
            this.executionKind = executionKind;
            this.childConversationId = childConversationId;
            this.recorder = recorder;
            this.namespace = namespace;
            this.terminalStatus = terminalStatus;
            this.started = started;
            this.result = result;
        }

        private int ordinal() { return ordinal; }
        private AiWorkflowPlan.Task task() { return task; }
        private AiAgentDefinition definition() { return definition; }
        private String executionKind() { return executionKind; }
        private String childConversationId() { return childConversationId; }
        private AiTrajectoryRecorder recorder() { return recorder; }
        private Map<String, Object> namespace() { return namespace; }
        private AtomicReference<String> terminalStatus() { return terminalStatus; }
        private AtomicBoolean started() { return started; }
        private AtomicReference<AiMultiAgentWorkerResult> result() { return result; }
    }
}

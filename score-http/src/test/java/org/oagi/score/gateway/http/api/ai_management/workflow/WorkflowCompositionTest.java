package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowCompositionTest {

    @Test
    void composesRoutingParallelizationAndOrchestrationInsideAChain() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Workflow routed = new RoutingWorkflow("route", ignored -> "specialist", Map.of(
                    "specialist", direct("routed", ignored -> "classified")));
            Workflow parallel = new ParallelizationWorkflow("parallel", List.of(
                    direct("left", context -> "left:" + upstream(context)),
                    direct("right", context -> "right:" + upstream(context))),
                    executor, Duration.ofSeconds(2), (context, results) -> {
                String output = results.stream().map(WorkflowResult::output)
                        .sorted().reduce((left, right) -> left + "|" + right).orElseThrow();
                return WorkflowResult.success("parallel", output, Map.of(), results);
            });
            Workflow orchestrated = new OrchestratorWorkersWorkflow("orchestrator", context -> List.of(
                    direct("worker-a", ignored -> "worker-a:" + upstream(context)),
                    direct("worker-b", ignored -> "worker-b:" + upstream(context))),
                    executor, Duration.ofSeconds(2), (context, results) ->
                    WorkflowResult.success("orchestrator", "final:" + results.size()
                            + ":" + upstream(context), Map.of(), results));

            WorkflowResult result = new ChainWorkflow(
                    "root", List.of(routed, parallel, orchestrated))
                    .process(WorkflowContext.root(mock(WorkflowInvocation.class)));

            assertThat(result.output()).isEqualTo(
                    "final:2:left:classified|right:classified");
            assertThat(result.children()).extracting(WorkflowResult::workflowId)
                    .containsExactly("route", "parallel", "orchestrator");
        }
    }

    @Test
    void evaluatorOptimizerReplansUntilTheEvaluatorAcceptsTheResult() {
        AtomicInteger planned = new AtomicInteger();
        EvaluatorOptimizerWorkflow workflow = new EvaluatorOptimizerWorkflow(
                "loop", 3,
                (context, iteration, attempts) -> direct("attempt-" + iteration,
                        ignored -> "result-" + planned.incrementAndGet()),
                (context, result, iteration) -> new EvaluatorOptimizerWorkflow.Evaluation(
                        iteration == 2, iteration == 1 ? "missing verification" : null,
                        iteration == 1 ? "verify the result" : null),
                (context, result, evaluation, iteration) -> context.withUpstream(List.of(result)));

        WorkflowResult result = workflow.process(
                WorkflowContext.root(mock(WorkflowInvocation.class)));

        assertThat(result.output()).isEqualTo("result-2");
        assertThat(result.metadata()).containsEntry("workflow_iterations", 2)
                .containsEntry("evaluation_status", "complete")
                .containsEntry("controller_workflow", "evaluator_optimizer");
    }

    @Test
    void directWorkflowDelegatesToItsOperation() {
        WorkflowResult result = direct("direct", ignored -> "done")
                .process(context());

        assertThat(result.workflowId()).isEqualTo("direct");
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void chainAndRoutingPreserveTheSelectedAgentExecutionMetadata() {
        Workflow selected = new DirectWorkflow("selected", ignored ->
                WorkflowResult.success("selected", "done", Map.of(
                        "agentId", "selected-agent",
                        "modelId", "selected-model",
                        "executionPurpose", "WORKER"), List.of()));
        Workflow routed = new RoutingWorkflow("route", ignored -> "selected",
                Map.of("selected", selected));

        WorkflowResult result = new ChainWorkflow("chain", List.of(routed))
                .process(context());

        assertThat(result.metadata())
                .containsEntry("agentId", "selected-agent")
                .containsEntry("modelId", "selected-model")
                .containsEntry("executionPurpose", "WORKER")
                .containsEntry("workflow", "chain")
                .containsEntry("step_count", 1);
        assertThat(result.children().getFirst().metadata())
                .containsEntry("workflow", "routing")
                .containsEntry("selected_route", "selected");
    }

    @Test
    void chainStopsBeforeLaterStepsAfterAFailure() {
        AtomicInteger laterCalls = new AtomicInteger();
        Workflow failing = new DirectWorkflow("failing", ignored ->
                WorkflowResult.failure("failing", new IllegalStateException("broken")));
        Workflow later = direct("later", ignored -> {
            laterCalls.incrementAndGet();
            return "should-not-run";
        });

        assertThatThrownBy(() -> new ChainWorkflow("chain", List.of(failing, later))
                .process(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broken");
        assertThat(laterCalls).hasValue(0);
    }

    @Test
    void parallelWorkflowPreservesPartialFailuresForTheAggregator() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Workflow failed = new DirectWorkflow("failed", ignored ->
                    WorkflowResult.failure("failed", new IllegalStateException("unavailable")));
            ParallelizationWorkflow workflow = new ParallelizationWorkflow(
                    "parallel", List.of(failed, direct("ok", ignored -> "evidence")),
                    executor, Duration.ofSeconds(1), (context, results) -> {
                assertThat(results).hasSize(2);
                assertThat(results.getFirst().successful()).isFalse();
                assertThat(results.getLast().output()).isEqualTo("evidence");
                return WorkflowResult.success("parallel", "partial", Map.of(), results);
            });

            assertThat(workflow.process(context()).output()).isEqualTo("partial");
        }
    }

    @Test
    void parallelWorkflowAppliesAndFinishesOneLifecycleContextPerBranch() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            WorkflowInvocation leftExecution = mock(WorkflowInvocation.class);
            WorkflowInvocation rightExecution = mock(WorkflowInvocation.class);
            List<WorkflowContext> finished = new CopyOnWriteArrayList<>();
            ParallelizationWorkflow.BranchLifecycle lifecycle =
                    new ParallelizationWorkflow.BranchLifecycle() {
                @Override
                public List<WorkflowContext> open(
                        WorkflowContext parent, List<String> branchIds) {
                    assertThat(branchIds).containsExactly("left", "right");
                    return List.of(WorkflowContext.root(leftExecution),
                            WorkflowContext.root(rightExecution));
                }

                @Override
                public void finished(WorkflowContext branch) {
                    finished.add(branch);
                }
            };
            List<WorkflowInvocation> observed = new CopyOnWriteArrayList<>();
            ParallelizationWorkflow workflow = new ParallelizationWorkflow(
                    "parallel", List.of(
                    direct("left", context -> {
                        observed.add(context.invocation());
                        return "left";
                    }),
                    direct("right", context -> {
                        observed.add(context.invocation());
                        return "right";
                    })), executor, Duration.ofSeconds(1),
                    (context, results) -> WorkflowResult.success(
                            "parallel", "done", Map.of(), results), lifecycle);

            assertThat(workflow.process(context()).output()).isEqualTo("done");
            assertThat(observed).containsExactlyInAnyOrder(leftExecution, rightExecution);
            assertThat(finished).hasSize(2)
                    .allMatch(WorkflowContext::concurrent);
        }
    }

    @Test
    void parallelWorkflowFinishesEveryOpenedLifecycleWhenSubmissionFails() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(org.mockito.ArgumentMatchers
                .<Callable<WorkflowResult>>any()))
                .thenThrow(new RejectedExecutionException("executor stopped"));
        WorkflowContext left = WorkflowContext.root(mock(WorkflowInvocation.class));
        WorkflowContext right = WorkflowContext.root(mock(WorkflowInvocation.class));
        List<WorkflowContext> finished = new CopyOnWriteArrayList<>();
        ParallelizationWorkflow.BranchLifecycle lifecycle =
                new ParallelizationWorkflow.BranchLifecycle() {
                    @Override
                    public List<WorkflowContext> open(
                            WorkflowContext parent, List<String> branchIds) {
                        return List.of(left, right);
                    }

                    @Override
                    public void finished(WorkflowContext branch) {
                        finished.add(branch);
                    }
                };
        ParallelizationWorkflow workflow = new ParallelizationWorkflow(
                "parallel", List.of(direct("left", ignored -> "left"),
                direct("right", ignored -> "right")), executor, Duration.ofSeconds(1),
                (context, results) -> WorkflowResult.success(
                        "parallel", "unused", Map.of(), results), lifecycle);

        assertThatThrownBy(() -> workflow.process(context()))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("executor stopped");
        assertThat(finished).hasSize(2).allMatch(WorkflowContext::concurrent);
    }

    @Test
    void parallelWorkflowFinishesALifecycleWhenCancellationWinsBeforeTheBranchStarts()
            throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger neverStartedCalls = new AtomicInteger();
        when(executor.submit(org.mockito.ArgumentMatchers
                .<Callable<WorkflowResult>>any())).thenAnswer(invocation -> {
            Callable<WorkflowResult> task = invocation.getArgument(0);
            if (submissions.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(task.call());
            }
            return new FutureTask<>(task);
        });
        List<WorkflowContext> finished = new CopyOnWriteArrayList<>();
        ParallelizationWorkflow.BranchLifecycle lifecycle =
                new ParallelizationWorkflow.BranchLifecycle() {
                    @Override
                    public List<WorkflowContext> open(
                            WorkflowContext parent, List<String> branchIds) {
                        return branchIds.stream()
                                .map(ignored -> WorkflowContext.root(
                                        mock(WorkflowInvocation.class)))
                                .toList();
                    }

                    @Override
                    public void finished(WorkflowContext branch) {
                        finished.add(branch);
                    }
                };
        ParallelizationWorkflow workflow = new ParallelizationWorkflow(
                "parallel", List.of(direct("fast", ignored -> "fast"),
                direct("never-started", ignored -> {
                    neverStartedCalls.incrementAndGet();
                    return "unexpected";
                })), executor, Duration.ofMillis(20),
                (context, results) -> WorkflowResult.success(
                        "parallel", "partial", Map.of(), results), lifecycle);

        assertThat(workflow.process(context()).output()).isEqualTo("partial");
        assertThat(neverStartedCalls).hasValue(0);
        assertThat(finished).hasSize(2).allMatch(WorkflowContext::concurrent);
    }

    @Test
    void orchestratorWorkersPropagatesTheParallelBranchLifecycle() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            WorkflowInvocation firstExecution = mock(WorkflowInvocation.class);
            WorkflowInvocation secondExecution = mock(WorkflowInvocation.class);
            List<WorkflowContext> finished = new CopyOnWriteArrayList<>();
            ParallelizationWorkflow.BranchLifecycle lifecycle =
                    new ParallelizationWorkflow.BranchLifecycle() {
                @Override
                public List<WorkflowContext> open(
                        WorkflowContext parent, List<String> branchIds) {
                    assertThat(branchIds).containsExactly("worker-a", "worker-b");
                    return List.of(WorkflowContext.root(firstExecution),
                            WorkflowContext.root(secondExecution));
                }

                @Override
                public void finished(WorkflowContext branch) {
                    finished.add(branch);
                }
            };
            List<WorkflowInvocation> observed = new CopyOnWriteArrayList<>();
            OrchestratorWorkersWorkflow workflow = new OrchestratorWorkersWorkflow(
                    "orchestrator", ignored -> List.of(
                    direct("worker-a", context -> {
                        observed.add(context.invocation());
                        return "a";
                    }),
                    direct("worker-b", context -> {
                        observed.add(context.invocation());
                        return "b";
                    })), executor, Duration.ofSeconds(1),
                    (context, results) -> WorkflowResult.success(
                            "orchestrator", "done", Map.of(), results), lifecycle);

            assertThat(workflow.process(context()).output()).isEqualTo("done");
            assertThat(observed).containsExactlyInAnyOrder(firstExecution, secondExecution);
            assertThat(finished).hasSize(2);
        }
    }

    @Test
    void parallelWorkflowTimesOutOneBranchWithoutDiscardingSuccessfulEvidence() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch neverReleased = new CountDownLatch(1);
            Workflow blocked = direct("blocked", ignored -> {
                try {
                    neverReleased.await();
                    return "unexpected";
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", failure);
                }
            });
            ParallelizationWorkflow workflow = new ParallelizationWorkflow(
                    "parallel", List.of(direct("fast", ignored -> "fast"), blocked),
                    executor, Duration.ofMillis(30), (context, results) -> {
                assertThat(results.getFirst().successful()).isTrue();
                assertThat(results.getLast().failure())
                        .hasMessageContaining("timed out");
                return WorkflowResult.success("parallel", "degraded", Map.of(), results);
            });

            assertThat(workflow.process(context()).output()).isEqualTo("degraded");
        }
    }

    @Test
    void parallelWorkflowHarvestsACompletedBranchAfterTheDeadlineIsExhausted() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch neverReleased = new CountDownLatch(1);
            Workflow blocked = direct("blocked", ignored -> {
                try {
                    neverReleased.await();
                    return "unexpected";
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", failure);
                }
            });
            ParallelizationWorkflow workflow = new ParallelizationWorkflow(
                    "parallel", List.of(blocked, direct("fast", ignored -> "fast")),
                    executor, Duration.ofMillis(50), (context, results) -> {
                assertThat(results.getFirst().failure())
                        .hasMessageContaining("timed out");
                assertThat(results.getLast().successful()).isTrue();
                assertThat(results.getLast().output()).isEqualTo("fast");
                return WorkflowResult.success("parallel", "harvested", Map.of(), results);
            });

            assertThat(workflow.process(context()).output()).isEqualTo("harvested");
        }
    }

    @Test
    void parallelWorkflowFailsWhenEveryBranchFails() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Workflow first = new DirectWorkflow("first", ignored ->
                    WorkflowResult.failure("first", new IllegalArgumentException("first")));
            Workflow second = new DirectWorkflow("second", ignored -> {
                throw new IllegalStateException("second");
            });

            assertThatThrownBy(() -> new ParallelizationWorkflow(
                    "parallel", List.of(first, second), executor, Duration.ofSeconds(1),
                    (context, results) -> WorkflowResult.success(
                            "unused", "unused", Map.of(), results)).process(context()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("All parallel workflow branches failed.");
        }
    }

    @Test
    void routingRejectsAnUnknownSelectedRoute() {
        RoutingWorkflow workflow = new RoutingWorkflow(
                "routing", ignored -> "missing",
                Map.of("known", direct("known", ignored -> "done")));

        assertThatThrownBy(() -> workflow.process(context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void orchestratorRejectsNoWorkersAndAggregatesOneWorker() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            OrchestratorWorkersWorkflow empty = new OrchestratorWorkersWorkflow(
                    "empty", ignored -> List.of(), executor, Duration.ofSeconds(1),
                    (context, results) -> results.getFirst());
            assertThatThrownBy(() -> empty.process(context()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no workers");

            OrchestratorWorkersWorkflow single = new OrchestratorWorkersWorkflow(
                    "single", ignored -> List.of(direct("worker", workerContext -> "evidence")),
                    executor, Duration.ofSeconds(1), (context, results) ->
                    WorkflowResult.success("single", "synthesized:" + results.getFirst().output(),
                            Map.of(), results));
            assertThat(single.process(context()).output()).isEqualTo("synthesized:evidence");
        }
    }

    @Test
    void evaluatorOptimizerReportsTheIterationLimit() {
        EvaluatorOptimizerWorkflow workflow = new EvaluatorOptimizerWorkflow(
                "loop", 2,
                (context, iteration, attempts) -> direct(
                        "attempt-" + iteration, ignored -> "result-" + iteration),
                (context, result, iteration) -> new EvaluatorOptimizerWorkflow.Evaluation(
                        false, "more work", "continue"),
                (context, result, evaluation, iteration) -> context.withUpstream(List.of(result)));

        WorkflowResult result = workflow.process(context());

        assertThat(result.output()).isEqualTo("result-2");
        assertThat(result.children()).hasSize(2);
        assertThat(result.metadata()).containsEntry("workflow_iterations", 2)
                .containsEntry("evaluation_status", "iteration_limit")
                .containsEntry("remaining_objective", "continue");
    }

    @Test
    void evaluatorOptimizerSalvagesThePriorAttemptWhenALaterIterationThrows() {
        EvaluatorOptimizerWorkflow workflow = new EvaluatorOptimizerWorkflow(
                "loop", 3,
                (context, iteration, attempts) -> {
                    if (iteration == 2) {
                        throw new IllegalStateException("planner broke");
                    }
                    return direct("attempt-" + iteration, ignored -> "result-" + iteration);
                },
                (context, result, iteration) -> new EvaluatorOptimizerWorkflow.Evaluation(
                        false, "missing verification", "verify the result"),
                (context, result, evaluation, iteration) -> context.withUpstream(List.of(result)));

        WorkflowResult result = workflow.process(context());

        assertThat(result.output()).isEqualTo("result-1");
        assertThat(result.metadata()).containsEntry("workflow_iterations", 2)
                .containsEntry("evaluation_status", "iteration_failed")
                .containsEntry("remaining_objective", "verify the result");
    }

    @Test
    void evaluatorOptimizerSalvagesThePriorAttemptWhenALaterIterationFails() {
        EvaluatorOptimizerWorkflow workflow = new EvaluatorOptimizerWorkflow(
                "loop", 3,
                (context, iteration, attempts) -> iteration == 2
                        ? new DirectWorkflow("attempt-2", ignored -> WorkflowResult.failure(
                                "attempt-2", new IllegalStateException("attempt broke")))
                        : direct("attempt-" + iteration, ignored -> "result-" + iteration),
                (context, result, iteration) -> new EvaluatorOptimizerWorkflow.Evaluation(
                        false, "missing verification", "verify the result"),
                (context, result, evaluation, iteration) -> context.withUpstream(List.of(result)));

        WorkflowResult result = workflow.process(context());

        assertThat(result.output()).isEqualTo("result-1");
        assertThat(result.metadata()).containsEntry("workflow_iterations", 2)
                .containsEntry("evaluation_status", "iteration_failed")
                .containsEntry("remaining_objective", "verify the result");
    }

    @Test
    void evaluatorOptimizerStillFailsWhenTheFirstIterationFails() {
        EvaluatorOptimizerWorkflow workflow = new EvaluatorOptimizerWorkflow(
                "loop", 3,
                (context, iteration, attempts) -> new DirectWorkflow("attempt-1", ignored ->
                        WorkflowResult.failure("attempt-1", new IllegalStateException("broken"))),
                (context, result, iteration) -> new EvaluatorOptimizerWorkflow.Evaluation(
                        true, null, null),
                (context, result, evaluation, iteration) -> context);

        assertThatThrownBy(() -> workflow.process(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broken");
    }

    @Test
    void evaluatorOptimizerPropagatesCancellationFromALaterIteration() {
        EvaluatorOptimizerWorkflow workflow = new EvaluatorOptimizerWorkflow(
                "loop", 3,
                (context, iteration, attempts) -> {
                    if (iteration == 2) {
                        throw new CancellationException("cancelled");
                    }
                    return direct("attempt-" + iteration, ignored -> "result-" + iteration);
                },
                (context, result, iteration) -> new EvaluatorOptimizerWorkflow.Evaluation(
                        false, "more work", "continue"),
                (context, result, evaluation, iteration) -> context.withUpstream(List.of(result)));

        assertThatThrownBy(() -> workflow.process(context()))
                .isInstanceOf(CancellationException.class)
                .hasMessage("cancelled");
    }

    private DirectWorkflow direct(
            String id, java.util.function.Function<WorkflowContext, String> operation) {
        return new DirectWorkflow(id, context -> WorkflowResult.success(
                id, operation.apply(context), Map.of(), List.of()));
    }

    private String upstream(WorkflowContext context) {
        return context.upstreamResults().getLast().output();
    }

    private WorkflowContext context() {
        return WorkflowContext.root(mock(WorkflowInvocation.class));
    }
}

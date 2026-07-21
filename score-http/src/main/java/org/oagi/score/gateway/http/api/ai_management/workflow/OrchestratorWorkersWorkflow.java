package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/** Plans worker workflows for the current input, runs them, and synthesizes their outputs. */
public final class OrchestratorWorkersWorkflow implements Workflow {

    private final String id;
    private final Function<WorkflowContext, List<Workflow>> workerPlanner;
    private final ExecutorService executor;
    private final Duration timeout;
    private final WorkflowAggregator aggregator;

    public OrchestratorWorkersWorkflow(
            String id, Function<WorkflowContext, List<Workflow>> workerPlanner,
            ExecutorService executor, Duration timeout, WorkflowAggregator aggregator) {
        this.id = Objects.requireNonNull(id, "id");
        this.workerPlanner = Objects.requireNonNull(workerPlanner, "workerPlanner");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("An orchestrator workflow timeout must be positive.");
        }
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WorkflowResult process(WorkflowContext context) {
        Objects.requireNonNull(context, "context");
        List<Workflow> workers = List.copyOf(Objects.requireNonNull(
                workerPlanner.apply(context), "The orchestrator worker planner returned null."));
        if (workers.isEmpty()) {
            throw new IllegalStateException("The orchestrator produced no workers.");
        }
        if (workers.size() == 1) {
            WorkflowResult result = Objects.requireNonNull(
                    workers.getFirst().process(context.concurrentBranch()),
                    "The orchestrator worker returned null.");
            if (!result.successful()) {
                throw result.failure();
            }
            return Objects.requireNonNull(aggregator.aggregate(context, List.of(result)),
                    () -> "Orchestrator workflow '" + id + "' aggregator returned null.");
        }
        return new ParallelizationWorkflow(id + ":workers", workers,
                executor, timeout, aggregator).process(context);
    }
}

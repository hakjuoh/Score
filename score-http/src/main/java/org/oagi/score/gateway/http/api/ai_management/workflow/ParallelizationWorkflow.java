package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs independent children concurrently and delegates result reduction to an aggregator. */
public final class ParallelizationWorkflow implements Workflow {

    private final String id;
    private final List<Workflow> branches;
    private final ExecutorService executor;
    private final Duration timeout;
    private final WorkflowAggregator aggregator;

    public ParallelizationWorkflow(String id, List<Workflow> branches,
                                   ExecutorService executor, Duration timeout,
                                   WorkflowAggregator aggregator) {
        this.id = Objects.requireNonNull(id, "id");
        this.branches = branches != null ? List.copyOf(branches) : List.of();
        if (this.branches.size() < 2) {
            throw new IllegalArgumentException("A parallel workflow requires at least two branches.");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("A parallel workflow timeout must be positive.");
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
        WorkflowContext branchContext = context.concurrentBranch();
        List<Future<WorkflowResult>> futures = branches.stream()
                .map(branch -> executor.submit(() -> Objects.requireNonNull(
                        branch.process(branchContext),
                        () -> "Workflow branch '" + branch.id() + "' returned null.")))
                .toList();
        long deadline = System.nanoTime() + timeout.toNanos();
        List<WorkflowResult> results = new ArrayList<>(branches.size());
        try {
            for (int index = 0; index < futures.size(); index++) {
                Future<WorkflowResult> future = futures.get(index);
                String branchId = branches.get(index).id();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    if (future.isDone()) {
                        try {
                            results.add(future.get());
                        } catch (ExecutionException failure) {
                            Throwable cause = failure.getCause();
                            results.add(WorkflowResult.failure(branchId,
                                    cause instanceof RuntimeException runtime ? runtime
                                            : new IllegalStateException("Workflow branch failed.", cause)));
                        }
                    } else {
                        future.cancel(true);
                        results.add(WorkflowResult.failure(branchId,
                                new IllegalStateException("Workflow branch timed out.")));
                    }
                    continue;
                }
                try {
                    results.add(future.get(remaining, TimeUnit.NANOSECONDS));
                } catch (TimeoutException failure) {
                    future.cancel(true);
                    results.add(WorkflowResult.failure(branchId,
                            new IllegalStateException("Workflow branch timed out.", failure)));
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    results.add(WorkflowResult.failure(branchId,
                            cause instanceof RuntimeException runtime ? runtime
                                    : new IllegalStateException("Workflow branch failed.", cause)));
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            futures.forEach(candidate -> candidate.cancel(true));
            throw new CancellationException("Parallel workflow was interrupted.");
        } finally {
            futures.stream().filter(candidate -> !candidate.isDone())
                    .forEach(candidate -> candidate.cancel(true));
        }
        if (results.stream().noneMatch(WorkflowResult::successful)) {
            throw new IllegalStateException("All parallel workflow branches failed.");
        }
        return Objects.requireNonNull(aggregator.aggregate(context, List.copyOf(results)),
                () -> "Parallel workflow '" + id + "' aggregator returned null.");
    }
}

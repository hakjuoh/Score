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
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs independent children concurrently and delegates result reduction to an aggregator. */
public final class ParallelizationWorkflow implements Workflow {

    private final String id;
    private final List<Workflow> branches;
    private final ExecutorService executor;
    private final Duration timeout;
    private final WorkflowAggregator aggregator;
    private final BranchLifecycle branchLifecycle;

    public ParallelizationWorkflow(String id, List<Workflow> branches,
                                   ExecutorService executor, Duration timeout,
                                   WorkflowAggregator aggregator) {
        this(id, branches, executor, timeout, aggregator, BranchLifecycle.NOOP);
    }

    public ParallelizationWorkflow(String id, List<Workflow> branches,
                                   ExecutorService executor, Duration timeout,
                                   WorkflowAggregator aggregator,
                                   BranchLifecycle branchLifecycle) {
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
        this.branchLifecycle = Objects.requireNonNull(branchLifecycle, "branchLifecycle");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WorkflowResult process(WorkflowContext context) {
        Objects.requireNonNull(context, "context");
        List<WorkflowContext> branchContexts = branchLifecycle.open(
                context, branches.stream().map(Workflow::id).toList());
        if (branchContexts == null || branchContexts.size() != branches.size()) {
            throw new IllegalStateException("The parallel branch lifecycle returned invalid contexts.");
        }
        List<Future<WorkflowResult>> futures = new ArrayList<>(branches.size());
        List<BranchExecution> executions = new ArrayList<>(branches.size());
        for (int index = 0; index < branches.size(); index++) {
            executions.add(new BranchExecution(branches.get(index), Objects.requireNonNull(
                    branchContexts.get(index), "parallel branch context").concurrentBranch()));
        }
        try {
            for (BranchExecution execution : executions) {
                futures.add(executor.submit(() -> execution.run(branchLifecycle)));
            }
        } catch (RuntimeException submissionFailure) {
            futures.forEach(candidate -> candidate.cancel(true));
            executions.forEach(execution -> execution.finish(branchLifecycle, submissionFailure));
            throw submissionFailure;
        }
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
                        } catch (CancellationException failure) {
                            executions.get(index).finish(branchLifecycle, failure);
                            results.add(WorkflowResult.failure(branchId, failure));
                        } catch (ExecutionException failure) {
                            Throwable cause = failure.getCause();
                            results.add(WorkflowResult.failure(branchId,
                                    cause instanceof RuntimeException runtime ? runtime
                                            : new IllegalStateException("Workflow branch failed.", cause)));
                        }
                    } else {
                        RuntimeException timeoutFailure =
                                new IllegalStateException("Workflow branch timed out.");
                        cancelAndFinish(future, executions.get(index), timeoutFailure);
                        results.add(WorkflowResult.failure(branchId, timeoutFailure));
                    }
                    continue;
                }
                try {
                    results.add(future.get(remaining, TimeUnit.NANOSECONDS));
                } catch (TimeoutException failure) {
                    RuntimeException timeoutFailure =
                            new IllegalStateException("Workflow branch timed out.", failure);
                    cancelAndFinish(future, executions.get(index), timeoutFailure);
                    results.add(WorkflowResult.failure(branchId, timeoutFailure));
                } catch (CancellationException failure) {
                    executions.get(index).finish(branchLifecycle, failure);
                    results.add(WorkflowResult.failure(branchId, failure));
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    results.add(WorkflowResult.failure(branchId,
                            cause instanceof RuntimeException runtime ? runtime
                                    : new IllegalStateException("Workflow branch failed.", cause)));
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            CancellationException cancellation =
                    new CancellationException("Parallel workflow was interrupted.");
            for (int index = 0; index < futures.size(); index++) {
                cancelAndFinish(futures.get(index), executions.get(index), cancellation);
            }
            throw cancellation;
        } finally {
            CancellationException cancellation =
                    new CancellationException("Parallel workflow branch was cancelled during cleanup.");
            for (int index = 0; index < futures.size(); index++) {
                Future<WorkflowResult> future = futures.get(index);
                if (!future.isDone()) {
                    cancelAndFinish(future, executions.get(index), cancellation);
                }
            }
        }
        if (results.stream().noneMatch(WorkflowResult::successful)) {
            throw new IllegalStateException("All parallel workflow branches failed.");
        }
        return Objects.requireNonNull(aggregator.aggregate(context, List.copyOf(results)),
                () -> "Parallel workflow '" + id + "' aggregator returned null.");
    }

    private void cancelAndFinish(Future<WorkflowResult> future, BranchExecution execution,
                                 RuntimeException cancellation) {
        future.cancel(true);
        execution.finish(branchLifecycle, cancellation);
    }

    /** Hooks parallel branch identity into external barriers without coupling the workflow package to them. */
    public interface BranchLifecycle {

        BranchLifecycle NOOP = new BranchLifecycle() {
            @Override
            public List<WorkflowContext> open(WorkflowContext parent, List<String> branchIds) {
                return branchIds.stream().map(ignored -> parent.concurrentBranch()).toList();
            }

            @Override
            public void finished(WorkflowContext branch) {
            }
        };

        List<WorkflowContext> open(WorkflowContext parent, List<String> branchIds);

        void finished(WorkflowContext branch);
    }

    private static final class BranchExecution {
        private final Workflow workflow;
        private final WorkflowContext context;
        private final AtomicBoolean finished = new AtomicBoolean();

        private BranchExecution(Workflow workflow, WorkflowContext context) {
            this.workflow = workflow;
            this.context = context;
        }

        private WorkflowResult run(BranchLifecycle lifecycle) {
            try {
                return Objects.requireNonNull(workflow.process(context),
                        () -> "Workflow branch '" + workflow.id() + "' returned null.");
            } finally {
                finish(lifecycle, null);
            }
        }

        private void finish(BranchLifecycle lifecycle, RuntimeException primaryFailure) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                lifecycle.finished(context);
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }
}

package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Repeatedly plans and executes a workflow, evaluates its result, and replans
 * with feedback until the evaluator accepts the result or the iteration cap is reached.
 */
public final class EvaluatorOptimizerWorkflow implements Workflow {

    private final String id;
    private final int maximumIterations;
    private final IterationPlanner planner;
    private final IterationEvaluator evaluator;
    private final ContextOptimizer optimizer;

    public EvaluatorOptimizerWorkflow(
            String id, int maximumIterations, IterationPlanner planner,
            IterationEvaluator evaluator, ContextOptimizer optimizer) {
        this.id = Objects.requireNonNull(id, "id");
        if (maximumIterations < 1) {
            throw new IllegalArgumentException("maximumIterations must be positive.");
        }
        this.maximumIterations = maximumIterations;
        this.planner = Objects.requireNonNull(planner, "planner");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WorkflowResult process(WorkflowContext context) {
        Objects.requireNonNull(context, "context");
        List<WorkflowResult> attempts = new ArrayList<>();
        WorkflowContext current = context;
        Evaluation lastEvaluation = null;
        for (int iteration = 1; iteration <= maximumIterations; iteration++) {
            WorkflowResult result = attempt(current, iteration, attempts);
            if (!result.successful()) {
                if (attempts.isEmpty()) {
                    throw result.failure();
                }
                return completed(attempts.getLast(), attempts, iteration,
                        "iteration_failed", lastEvaluation);
            }
            attempts.add(result);
            lastEvaluation = Objects.requireNonNull(
                    evaluator.evaluate(current, result, iteration),
                    "The workflow evaluator returned null.");
            if (lastEvaluation.complete()) {
                return completed(result, attempts, iteration, "complete", lastEvaluation);
            }
            if (iteration < maximumIterations) {
                current = Objects.requireNonNull(
                        optimizer.next(current, result, lastEvaluation, iteration),
                        "The workflow optimizer returned null.");
            }
        }
        WorkflowResult last = attempts.getLast();
        return completed(last, attempts, maximumIterations,
                "iteration_limit", lastEvaluation);
    }

    private WorkflowResult attempt(
            WorkflowContext current, int iteration, List<WorkflowResult> attempts) {
        try {
            Workflow workflow = Objects.requireNonNull(
                    planner.plan(current, iteration, List.copyOf(attempts)),
                    "The iteration planner returned null.");
            return Objects.requireNonNull(workflow.process(current),
                    "The planned workflow returned null.");
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (RuntimeException failure) {
            return WorkflowResult.failure(id, failure);
        }
    }

    private WorkflowResult completed(
            WorkflowResult result, List<WorkflowResult> attempts, int iterations,
            String status, Evaluation evaluation) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("controller_workflow", "evaluator_optimizer");
        metadata.put("workflow_iterations", iterations);
        metadata.put("evaluation_status", status);
        if (evaluation != null && evaluation.nextObjective() != null) {
            metadata.put("remaining_objective", evaluation.nextObjective());
        }
        return WorkflowResult.success(id, result.output(), metadata, attempts);
    }

    @FunctionalInterface
    public interface IterationPlanner {
        Workflow plan(WorkflowContext context, int iteration,
                      List<WorkflowResult> previousAttempts);
    }

    @FunctionalInterface
    public interface IterationEvaluator {
        Evaluation evaluate(WorkflowContext context, WorkflowResult result, int iteration);
    }

    @FunctionalInterface
    public interface ContextOptimizer {
        WorkflowContext next(WorkflowContext context, WorkflowResult result,
                             Evaluation evaluation, int iteration);
    }

    public record Evaluation(boolean complete, String feedback, String nextObjective) {}
}

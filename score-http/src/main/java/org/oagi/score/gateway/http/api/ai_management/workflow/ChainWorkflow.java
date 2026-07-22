package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runs children in order and passes each output to the next child. */
public final class ChainWorkflow implements Workflow {

    private final String id;
    private final List<Workflow> steps;

    public ChainWorkflow(String id, List<Workflow> steps) {
        this.id = Objects.requireNonNull(id, "id");
        this.steps = steps != null ? List.copyOf(steps) : List.of();
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("A chain workflow requires at least one step.");
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WorkflowResult process(WorkflowContext context) {
        Objects.requireNonNull(context, "context");
        List<WorkflowResult> results = new ArrayList<>(steps.size());
        WorkflowContext current = context;
        for (Workflow step : steps) {
            WorkflowResult result = Objects.requireNonNull(step.process(current),
                    () -> "Workflow step '" + step.id() + "' returned null.");
            if (!result.successful()) {
                throw result.failure();
            }
            results.add(result);
            current = context.withUpstream(List.of(result));
        }
        WorkflowResult last = results.getLast();
        Map<String, Object> metadata = new LinkedHashMap<>(last.metadata());
        metadata.put("workflow", "chain");
        metadata.put("step_count", steps.size());
        return WorkflowResult.success(id, last.output(),
                metadata, results);
    }
}

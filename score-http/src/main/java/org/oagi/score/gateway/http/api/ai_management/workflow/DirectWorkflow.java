package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.Objects;
import java.util.function.Function;

/** Leaf workflow backed by one application-defined operation. */
public final class DirectWorkflow implements Workflow {

    private final String id;
    private final Function<WorkflowContext, WorkflowResult> operation;

    public DirectWorkflow(String id, Function<WorkflowContext, WorkflowResult> operation) {
        this.id = Objects.requireNonNull(id, "id");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WorkflowResult process(WorkflowContext context) {
        return Objects.requireNonNull(operation.apply(Objects.requireNonNull(context, "context")),
                () -> "Direct workflow '" + id + "' returned null.");
    }
}

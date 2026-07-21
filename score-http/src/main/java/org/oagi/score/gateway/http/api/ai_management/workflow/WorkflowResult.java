package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Output of one workflow node, including its child execution tree. */
public record WorkflowResult(String workflowId, String output,
                             Map<String, Object> metadata,
                             List<WorkflowResult> children,
                             RuntimeException failure) {

    public WorkflowResult {
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        children = children != null ? List.copyOf(children) : List.of();
    }

    public static WorkflowResult success(String workflowId, String output,
                                         Map<String, Object> metadata,
                                         List<WorkflowResult> children) {
        return new WorkflowResult(workflowId, output, metadata, children, null);
    }

    public static WorkflowResult failure(String workflowId, RuntimeException failure) {
        return new WorkflowResult(workflowId, null, Map.of(), List.of(),
                Objects.requireNonNull(failure, "failure"));
    }

    public boolean successful() {
        return failure == null;
    }
}

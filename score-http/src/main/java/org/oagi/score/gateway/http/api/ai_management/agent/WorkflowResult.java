package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Output of one Workflow node, including its child execution tree. */
public final class WorkflowResult {

    private final String workflowId;
    private final AgentOutput result;
    private final List<WorkflowResult> children;
    private final RuntimeException failure;

    public WorkflowResult(String workflowId, String output,
                          Map<String, Object> metadata,
                          List<WorkflowResult> children,
                          RuntimeException failure) {
        this(workflowId, new AgentOutput(output, metadata), children, failure);
    }

    private WorkflowResult(String workflowId, AgentOutput result,
                           List<WorkflowResult> children,
                           RuntimeException failure) {
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.result = Objects.requireNonNull(result, "result");
        this.children = children != null ? List.copyOf(children) : List.of();
        this.failure = failure;
    }

    public static WorkflowResult success(String workflowId, AgentOutput result,
                                         List<WorkflowResult> children) {
        return new WorkflowResult(workflowId, result, children, null);
    }

    public static WorkflowResult success(String workflowId, String output,
                                         Map<String, Object> metadata,
                                         List<WorkflowResult> children) {
        return success(workflowId, new AgentOutput(output, metadata), children);
    }

    public static WorkflowResult failure(String workflowId, RuntimeException failure) {
        return new WorkflowResult(workflowId, new AgentOutput(""), List.of(),
                Objects.requireNonNull(failure, "failure"));
    }

    public String workflowId() {
        return workflowId;
    }

    public AgentOutput result() {
        return result;
    }

    public String output() {
        return result.content();
    }

    public Map<String, Object> metadata() {
        return result.metadata();
    }

    public List<WorkflowResult> children() {
        return children;
    }

    public RuntimeException failure() {
        return failure;
    }

    public boolean successful() {
        return failure == null;
    }
}

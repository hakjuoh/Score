package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowResult;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Synthesizes and projects workflow results independently of recursive scheduling. */
final class WorkflowResults {

    private static final String PARTIAL_FAILURE_NOTICE =
            "Some requested steps could not be completed. Successful actions may already "
                    + "have taken effect; review the result before retrying incomplete steps.";

    private final AgentRunner agents;
    private final Agent synthesizer;

    WorkflowResults(AgentRunner agents, Agent synthesizer) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.synthesizer = synthesizer;
    }

    AgentOutput synthesize(AgentWorkflowContext parent, AiWorkflowPlan plan,
                           List<WorkflowResult> results, WorkflowRunBudget budget) {
        if (results.size() == 1 && results.getFirst().successful()) {
            return results.getFirst().result();
        }
        if (synthesizer == null) {
            return results.reversed().stream()
                    .filter(WorkflowResult::successful).findFirst().orElseThrow().result();
        }
        AgentWorkflowContext synthesis = parent.inWorkflow(plan,
                        Objects.requireNonNull(parent.location(), "Workflow location"))
                .withInputs(results);
        AgentDecision decision = budget.invoke(agents, synthesizer.callId(), synthesis);
        if (decision instanceof AgentDecision.Complete complete) return complete.result();
        throw new IllegalStateException("The Synthesizer Agent must complete its assigned unit.");
    }

    Completion complete(String workflowId, String nodeId, AgentOutput output,
                        List<WorkflowResult> children) {
        int completed = (int) children.stream().filter(WorkflowResult::successful).count();
        int directFailed = children.size() - completed;
        int failures = failureCount(children);
        Map<String, Object> metadata = new LinkedHashMap<>(output.metadata());
        metadata.put("workflow", workflowId);
        metadata.put("node_id", nodeId);
        metadata.put("completed", completed);
        metadata.put("direct_failed", directFailed);
        metadata.put("failed", failures);
        String answer = output.content();
        if (failures > 0) {
            metadata.put("partial_failure", true);
            metadata.put("partial_failure_count", failures);
            metadata.put("partial_failure_notice", true);
            answer = partialFailureAnswer(answer);
        }
        AgentOutput completedOutput = failures > 0
                ? new AgentOutput(answer, Map.copyOf(metadata))
                : output.withMetadata(Map.copyOf(metadata));
        return new Completion(WorkflowResult.success(workflowId, completedOutput, children),
                completed, directFailed, failures);
    }

    AgentOutput asCandidate(WorkflowResult result, AiWorkflowPlan plan, int iteration) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("workflow", plan.root().id());
        metadata.put("workflow_iteration", iteration);
        return result.result().withMetadata(Map.copyOf(metadata));
    }

    void publishFinal(AgentExecutionRecorder recorder, WorkflowRunBudget budget,
                      AiWorkflowPlan plan, int iteration, AgentOutput result) {
        if (plan == null || !result.passedOutputGuardrail(AgentOutputGuardrail.Scope.PUBLIC)) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("status", "result");
        metadata.put("workflow_iterations", iteration);
        recorder.callWhileActive(() -> {
            budget.checkpoint();
            recorder.workflowResult(result, Map.copyOf(metadata));
            return null;
        });
    }

    private int failureCount(List<WorkflowResult> results) {
        int failures = 0;
        for (WorkflowResult result : results) {
            if (!result.successful()) failures++;
            failures += failureCount(result.children());
        }
        return failures;
    }

    private String partialFailureAnswer(String answer) {
        String value = Objects.requireNonNullElse(answer, "").stripTrailing();
        if (value.contains(PARTIAL_FAILURE_NOTICE)) return value;
        return value + (value.isEmpty() ? "" : "\n\n") + PARTIAL_FAILURE_NOTICE;
    }

    record Completion(WorkflowResult result, int completed, int directFailed, int failures) { }
}

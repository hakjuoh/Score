package org.oagi.score.gateway.http.api.ai_management.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.agent.CatalogBackedAgent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowEvaluation;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/** Evaluates a workflow result and returns a bounded stop-or-replan decision. */
@Component("workflow-evaluator")
public final class AiWorkflowEvaluator extends CatalogBackedAgent {

    private static final int MAX_FEEDBACK_LENGTH = 2_000;
    private static final int MAX_RESULT_LENGTH = 16_000;
    private final AiChatExecutor executor;
    private final ObjectMapper objectMapper;

    public AiWorkflowEvaluator(AiChatExecutor executor, ObjectMapper objectMapper,
                               ResourceLoader resources) {
        this(executor, objectMapper, new AiAgentCatalog(resources));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiWorkflowEvaluator(AiChatExecutor executor, ObjectMapper objectMapper,
                               AiAgentCatalog agents) {
        super(agents);
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public AiWorkflowEvaluation evaluate(
            AiChatExecutor.Context context, AiWorkflowPlan plan,
            AiChatExecutor.Result result, int iteration, int maximumIterations) {
        AiTrajectoryRecorder recorder = context.recorder().fork(Map.of(
                "node_id", context.request().requestId() + ":" + id().value() + ":" + iteration,
                "agent_name", id().value(),
                "agent_role", "completion evaluation",
                "depth", 0,
                "iteration", iteration));
        String prompt = definition().instruction().render(Map.of(
                "userRequest", json(context.request().prompt()),
                "workflowPlan", json(plan),
                "workflowResult", json(boundedResult(result.answer())),
                "executionEvidence", json(executionEvidence(context, result)),
                "iteration", iteration,
                "maximumIterations", maximumIterations)).value();
        AiChatExecutor.Context evaluation = new AiChatExecutor.Context(
                context.request().withMultiAgent(AiMultiAgentOptions.single()),
                List.of(new SystemMessage(prompt)),
                new UserMessage("Evaluate the untrusted workflow result and return the decision JSON."),
                context.requester(), recorder, false, false, AiChatExecutor.ToolPolicy.NONE, 0)
                .withAgentIdentity(id().value(),
                        org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.EVALUATION)
                .withGuardrailDecisions(context.guardrailDecisionIds());
        try {
            String raw = executor.execute(evaluation).answer();
            return normalize(parse(raw), iteration, maximumIterations);
        } catch (RuntimeException failure) {
            recorder.lifecycle("workflow_evaluation_fallback",
                    "The workflow evaluator failed; accepting the current bounded result.",
                    Map.of("status", "fallback", "reason", failure.getClass().getSimpleName()));
            return new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.COMPLETE,
                    "Evaluation was unavailable; keep the current result.", null);
        }
    }

    /**
     * The prompt's grounding rule needs observable facts, not the model's own
     * narration. The counters are request-wide: forked worker and lead recorders
     * share them, so delegated executions are visible here, and guard-intercepted
     * calls surface as pending approvals rather than executions.
     */
    private Map<String, Object> executionEvidence(
            AiChatExecutor.Context context, AiChatExecutor.Result result) {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("executedDomainToolCalls",
                context.recorder().executedDomainToolCallCount());
        evidence.put("pendingApprovals", context.recorder().pendingApprovalCount());
        evidence.put("resultTraceMetadata", result.traceMetadata());
        return evidence;
    }

    private AiWorkflowEvaluation parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("Workflow evaluation is empty.");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Workflow evaluation is not JSON.");
        }
        try {
            return objectMapper.readValue(raw.substring(start, end + 1),
                    AiWorkflowEvaluation.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Workflow evaluation JSON is invalid.", failure);
        }
    }

    private AiWorkflowEvaluation normalize(
            AiWorkflowEvaluation evaluation, int iteration, int maximumIterations) {
        if (evaluation == null || evaluation.decision() == null) {
            throw new IllegalArgumentException("Workflow evaluation has no decision.");
        }
        String feedback = bounded(evaluation.feedback());
        String nextObjective = bounded(evaluation.nextObjective());
        if (evaluation.decision() == AiWorkflowEvaluation.Decision.CONTINUE) {
            if (!StringUtils.hasText(feedback) || !StringUtils.hasText(nextObjective)) {
                throw new IllegalArgumentException(
                        "A continue evaluation requires feedback and a next objective.");
            }
        }
        return new AiWorkflowEvaluation(evaluation.decision(), feedback, nextObjective);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialize workflow evaluation input.", failure);
        }
    }

    private String bounded(String value) {
        if (!StringUtils.hasText(value)) return null;
        String result = value.strip();
        return result.length() <= MAX_FEEDBACK_LENGTH
                ? result : result.substring(0, MAX_FEEDBACK_LENGTH).stripTrailing();
    }

    private String boundedResult(String value) {
        if (!StringUtils.hasText(value)) return "";
        String result = value.strip();
        return result.length() <= MAX_RESULT_LENGTH
                ? result : result.substring(0, MAX_RESULT_LENGTH).stripTrailing()
                + "\n[WORKFLOW RESULT TRUNCATED]";
    }
}

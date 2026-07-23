package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowEvaluation;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Decides whether a candidate completes the request or hands it back to the Planner Agent. */
@Component("workflow-evaluator")
public final class EvaluatorAgent extends CatalogBackedAgent implements WorkflowAgent {

    private static final int MAX_FEEDBACK_LENGTH = 2_000;
    private static final int MAX_RESULT_LENGTH = 16_000;
    private final AgentExecutionService execution;
    private final SpringAiModelCatalog models;
    private final ObjectMapper objectMapper;

    public EvaluatorAgent(AgentExecutionService execution, SpringAiModelCatalog models,
                          ObjectMapper objectMapper, AiAgentCatalog agents) {
        super(agents);
        this.execution = execution;
        this.models = models;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentDecision execute(AgentWorkflowContext context) {
        if (context.candidate() == null || context.workflow() == null) {
            throw new IllegalArgumentException("Evaluator Agent requires a Workflow candidate.");
        }
        if (context.iteration() >= context.maximumIterations()) {
            return new AgentDecision.Complete(context.candidate());
        }
        AiWorkflowEvaluation evaluation = evaluate(context);
        if (evaluation.complete()) return new AgentDecision.Complete(context.candidate());
        AiWorkflowFeedback feedback = new AiWorkflowFeedback(context.iteration(),
                context.workflow().root().id(), boundedResult(context.candidate().answer()),
                evaluation.feedback(), evaluation.nextObjective());
        return new AgentDecision.Handoff(AssistantAgent.PLANNER_ID, feedback);
    }

    private AiWorkflowEvaluation evaluate(AgentWorkflowContext context) {
        String prompt = definition().instruction().render().value();
        ResolvedAgent resolved = new ResolvedAgent(definition(),
                models.require(context.request().modelName()),
                new Instruction(prompt), ToolSet.empty());
        AgentInvocation invocation = new AgentInvocation(null, resolved,
                new AiMessage.User("UNTRUSTED_EVALUATION_INPUT\n" + json(Map.of(
                        "userRequest", context.request().prompt(),
                        "workflowPlan", context.workflow(),
                        "workflowResult", boundedResult(context.candidate().answer()),
                        "executionEvidence", executionEvidence(context),
                        "iteration", context.iteration(),
                        "maximumIterations", context.maximumIterations()))
                        + "\nEvaluate this input and return the decision JSON."),
                List.of(), scope(context), null, context.observationContext());
        try {
            AgentRunResult result = execution.execute(invocation);
            context.recordUsage(definition().name(), result);
            return normalize(parse(result.response().content()));
        } catch (CancellationException | AgentInputRefusedException terminal) {
            throw terminal;
        } catch (RuntimeException failure) {
            context.execution().recorder().lifecycle("workflow_evaluation_fallback",
                    "The Evaluator Agent was unavailable; accepting the current bounded result.",
                    Map.of("status", "fallback", "reason", failure.getClass().getSimpleName()));
            return new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.COMPLETE,
                    "Evaluation was unavailable; keep the current result.", null);
        }
    }

    private Map<String, Object> executionEvidence(AgentWorkflowContext context) {
        return Map.of(
                "executedDomainToolCalls",
                context.execution().recorder().executedDomainToolCallCount(),
                "pendingApprovals", context.execution().recorder().pendingApprovalCount(),
                "resultTraceMetadata", context.candidate().traceMetadata());
    }

    private AiWorkflowEvaluation parse(String raw) {
        if (!StringUtils.hasText(raw)) throw new IllegalArgumentException("Evaluation is empty.");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Evaluation is not JSON.");
        try {
            return objectMapper.readValue(raw.substring(start, end + 1),
                    AiWorkflowEvaluation.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Evaluation JSON is invalid.", failure);
        }
    }

    private AiWorkflowEvaluation normalize(AiWorkflowEvaluation evaluation) {
        if (evaluation == null || evaluation.decision() == null) {
            throw new IllegalArgumentException("Evaluation has no decision.");
        }
        String feedback = bounded(evaluation.feedback());
        String objective = bounded(evaluation.nextObjective());
        if (evaluation.decision() == AiWorkflowEvaluation.Decision.CONTINUE
                && (!StringUtils.hasText(feedback) || !StringUtils.hasText(objective))) {
            throw new IllegalArgumentException(
                    "A continue evaluation requires feedback and a next objective.");
        }
        return new AiWorkflowEvaluation(evaluation.decision(), feedback, objective);
    }

    private ExecutionScope scope(AgentWorkflowContext context) {
        return new ExecutionScope(context.request().requestId(),
                context.request().conversationId(), context.request().requesterId(), 0L,
                ExecutionScope.Purpose.EVALUATION,
                context.execution().guardrailDecisionIds());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialize Evaluator Agent input.", failure);
        }
    }

    private String bounded(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.strip();
        return normalized.length() <= MAX_FEEDBACK_LENGTH
                ? normalized : normalized.substring(0, MAX_FEEDBACK_LENGTH).stripTrailing();
    }

    private String boundedResult(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.strip();
        return normalized.length() <= MAX_RESULT_LENGTH ? normalized
                : normalized.substring(0, MAX_RESULT_LENGTH).stripTrailing()
                + "\n[WORKFLOW RESULT TRUNCATED]";
    }
}

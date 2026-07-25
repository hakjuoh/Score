package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowEvaluation;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Definition for the Agent that accepts a result or sends feedback to Planner. */
@Component("workflow-evaluator")
public final class EvaluatorAgent implements Agent {

    private static final int MAX_FEEDBACK_LENGTH = 2_000;
    private static final int MAX_RESULT_LENGTH = 16_000;

    private final ObjectMapper objectMapper;
    private final AgentDefinition definition;

    public EvaluatorAgent(ObjectMapper objectMapper, AiAgentCatalog agents) {
        this.objectMapper = objectMapper;
        AgentDefinition configured = agents.systemDefinition("workflow-evaluator");
        this.definition = new AgentDefinition(configured.id(), configured.name(),
                configured.description(), configured.instruction(), this::prepare,
                AgentToolHandler.none(), responses(), AgentGuardrails.none(), false);
    }

    @Override
    public AgentDefinition definition() {
        return definition;
    }

    private AgentRunRequest prepare(Agent agent, AgentWorkflowContext context) {
        if (context.candidate() == null || context.workflow() == null) {
            throw new IllegalArgumentException("Evaluator Agent requires a Workflow candidate.");
        }
        if (context.iteration() >= context.maximumIterations()) {
            return new AgentRunRequest.Skip(new AgentDecision.Complete(context.candidate()));
        }
        return new AgentRunRequest.Model(context.request().modelName(),
                definition.instruction().render(),
                new AiMessage.User("UNTRUSTED_EVALUATION_INPUT\n" + json(Map.of(
                        "userRequest", context.request().prompt(),
                        "workflowPlan", context.workflow(),
                        "workflowResult", boundedResult(context.candidate().content()),
                        "executionEvidence", executionEvidence(context),
                        "iteration", context.iteration(),
                        "maximumIterations", context.maximumIterations()))
                        + "\nEvaluate this input and return the decision JSON."),
                List.of(), scope(context), context.observationContext());
    }

    private AgentResponseHandler responses() {
        return new AgentResponseHandler() {
            @Override
            public AgentDecision handle(AgentResponseContext response) {
                AgentWorkflowContext context = response.workflow();
                AiWorkflowEvaluation evaluation;
                try {
                    evaluation = normalize(parse(response.result().response().content()));
                } catch (RuntimeException failure) {
                    context.execution().recorder().lifecycle("workflow_evaluation_fallback",
                            "The Evaluator Agent was unavailable; accepting the current bounded result.",
                            Map.of("status", "fallback", "reason", failure.getClass().getSimpleName()));
                    evaluation = new AiWorkflowEvaluation(AiWorkflowEvaluation.Decision.COMPLETE,
                            "Evaluation was unavailable; keep the current result.", null);
                }
                if (evaluation.complete()) return new AgentDecision.Complete(context.candidate());
                AiWorkflowFeedback feedback = new AiWorkflowFeedback(context.iteration(),
                        context.workflow().root().id(), boundedResult(context.candidate().content()),
                        evaluation.feedback(), evaluation.nextObjective());
                return new AgentDecision.Handoff(AssistantAgent.PLANNER_ID, feedback);
            }

            @Override
            public AgentDecision onFailure(AgentFailure failure) {
                if (failure.exception() instanceof CancellationException
                        || failure.exception() instanceof AgentGuardrailRefusedException) {
                    throw failure.exception();
                }
                AgentWorkflowContext context = failure.workflow();
                if (context.candidate() == null) throw failure.exception();
                context.execution().recorder().lifecycle("workflow_evaluation_fallback",
                        "The Evaluator Agent was unavailable; accepting the current bounded result.",
                        Map.of("status", "fallback", "reason",
                                failure.exception().getClass().getSimpleName()));
                return new AgentDecision.Complete(context.candidate());
            }
        };
    }

    private AiWorkflowEvaluation parse(String raw) {
        if (!StringUtils.hasText(raw)) throw new IllegalArgumentException("Evaluation is empty.");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Evaluation is not JSON.");
        try {
            return objectMapper.readValue(raw.substring(start, end + 1), AiWorkflowEvaluation.class);
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

    private Map<String, Object> executionEvidence(AgentWorkflowContext context) {
        return Map.of("executedDomainToolCalls",
                context.execution().recorder().executedDomainToolCallCount(),
                "pendingApprovals", context.execution().recorder().pendingApprovalCount(),
                "resultTraceMetadata", context.candidate().metadata());
    }

    private ExecutionScope scope(AgentWorkflowContext context) {
        return context.executionScope(ExecutionScope.Purpose.EVALUATION);
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

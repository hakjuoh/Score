package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.WorkflowPlanValidator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

/** Definition for the Agent that selects workers and recursive Workflows. */
@Component("workflow-planner")
public final class PlannerAgent implements Agent {

    private static final int MAX_INSTRUCTION_LENGTH = 4_000;
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_HISTORY_MESSAGE_LENGTH = 1_500;

    private final AiAgentCatalog agents;
    private final ObjectMapper objectMapper;
    private final WorkflowPlanValidator validator = new WorkflowPlanValidator();
    private final AgentDefinition definition;

    public PlannerAgent(AiAgentCatalog agents, ObjectMapper objectMapper) {
        this.agents = agents;
        this.objectMapper = objectMapper;
        AgentDefinition configured = agents.systemDefinition("workflow-planner");
        this.definition = new AgentDefinition(configured.id(), configured.name(),
                configured.description(), configured.instruction(), this::prepare,
                AgentToolHandler.none(), responses(), AgentGuardrails.none(), false);
    }

    @Override
    public AgentDefinition definition() {
        return definition;
    }

    private AgentRunRequest prepare(Agent agent, AgentWorkflowContext context) {
        return new AgentRunRequest.Model(context.request().modelName(),
                definition.instruction().render(),
                new AiMessage.User("UNTRUSTED_PLANNING_INPUT\n"
                        + json(planningParameters(context))
                        + "\nReturn the recursive Workflow JSON for this input."),
                List.of(), scope(context), context.observationContext());
    }

    private AgentResponseHandler responses() {
        return new AgentResponseHandler() {
            @Override
            public AgentDecision handle(AgentResponseContext response) {
                try {
                    AiWorkflowPlan parsed = normalize(parse(response.result().response().content()));
                    validate(parsed, response.workflow(), id -> {
                        agents.requireWorker(id);
                        return true;
                    });
                    return new AgentDecision.Delegate(parsed);
                } catch (RuntimeException failure) {
                    return fallbackDecision(response.workflow(), failure);
                }
            }

            @Override
            public AgentDecision onFailure(AgentFailure failure) {
                if (failure.exception() instanceof CancellationException
                        || failure.exception() instanceof AgentGuardrailRefusedException) {
                    throw failure.exception();
                }
                return fallbackDecision(failure.workflow(), failure.exception());
            }
        };
    }

    private AgentDecision fallbackDecision(AgentWorkflowContext context, RuntimeException failure) {
        context.execution().recorder().lifecycle("workflow_plan_fallback",
                "The Planner Agent returned an unusable Workflow; using a bounded fallback.",
                Map.of("status", "fallback", "reason", failure.getClass().getSimpleName()));
        return new AgentDecision.Delegate(fallback(context));
    }

    private Map<String, Object> planningParameters(AgentWorkflowContext context) {
        var request = context.request();
        String planningPrompt = planningPrompt(context);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("userRequest", planningPrompt);
        parameters.put("recentConversation", recentConversation(context));
        parameters.put("hasAttachments", request.hasAttachments());
        parameters.put("hasPageContext", request.hasPageContext());
        parameters.put("maximumAgents", request.maximumAgents());
        Optional<Integer> requiredAgentCount = requiredAgentCount(context);
        if (requiredAgentCount.isPresent()) {
            parameters.put("requiredAgentCount", requiredAgentCount.get());
        }
        parameters.put("strategyPreference", request.strategy());
        parameters.put("workflowPreference", request.workflowPreference() != null
                ? request.workflowPreference() : "automatic");
        parameters.put("priorWorkflowAttempts", context.feedback());
        parameters.put("registeredAgents", agents.workers().stream().map(agent -> Map.of(
                "id", agent.id(), "name", agent.name(),
                "description", agent.description())).toList());
        return Map.copyOf(parameters);
    }

    private AiWorkflowPlan parse(String raw) {
        if (!StringUtils.hasText(raw)) throw new IllegalArgumentException("Workflow plan is empty.");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Workflow plan is not JSON.");
        try {
            return objectMapper.readValue(raw.substring(start, end + 1), AiWorkflowPlan.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Workflow plan JSON is invalid.", failure);
        }
    }

    private void validate(AiWorkflowPlan plan, AgentWorkflowContext context,
                          Predicate<String> assignableAgent) {
        Optional<Integer> requiredAgentCount = requiredAgentCount(context);
        if (requiredAgentCount.isPresent()) {
            validator.validateExactAgentCalls(plan, requiredAgentCount.get(),
                    assignableAgent);
        } else {
            validator.validate(plan, context.request().maximumAgents(), assignableAgent);
        }
    }

    private AiWorkflowPlan normalize(AiWorkflowPlan plan) {
        if (plan == null || plan.root() == null) {
            throw new IllegalArgumentException("The Planner Agent returned no root Workflow.");
        }
        AiWorkflowPlan.WorkflowDefinition root = normalizeWorkflow(plan.root());
        return new AiWorkflowPlan(root,
                requiredGuide(plan.guideMessage(), "workflow guideMessage", 180),
                requiredGuide(plan.synthesisGuideMessage(), "synthesisGuideMessage", 180));
    }

    private AiWorkflowPlan.WorkflowDefinition normalizeWorkflow(
            AiWorkflowPlan.WorkflowDefinition workflow) {
        List<AiWorkflowPlan.Member> normalized = new ArrayList<>();
        for (AiWorkflowPlan.Member member : workflow.members()) {
            if (member.agent() != null) {
                normalized.add(new AiWorkflowPlan.Member(member.id(),
                        normalizeTask(member.agent()), null));
            } else {
                normalized.add(new AiWorkflowPlan.Member(member.id(), null,
                        normalizeWorkflow(member.workflow())));
            }
        }
        return new AiWorkflowPlan.WorkflowDefinition(
                workflow.id(), normalized, workflow.edges());
    }

    private AiWorkflowPlan.AgentTask normalizeTask(AiWorkflowPlan.AgentTask task) {
        AiAgentDefinition definition = agents.requireWorker(task.agentId());
        return new AiWorkflowPlan.AgentTask(definition.id(), bounded(task.label(), 100),
                bounded(task.instruction(), MAX_INSTRUCTION_LENGTH),
                requiredGuide(task.guideMessage(), "agent guideMessage", 180),
                bounded(task.activeVerb(), 32),
                bounded(task.completedVerb(), 32), task.toolAccess(), task.delegation());
    }

    private AiWorkflowPlan fallback(AgentWorkflowContext context) {
        AiAgentDefinition agent = agents.defaultAgent();
        int requested = requiredAgentCount(context).orElseGet(() ->
                context.request().delegationRequested()
                        ? context.request().maximumAgents() : 1);
        List<AiWorkflowPlan.Member> members = new ArrayList<>();
        String scope = context.assignment() != null
                ? "the assigned task: " + context.assignment().instruction()
                : "the user's request";
        for (int ordinal = 1; ordinal <= requested; ordinal++) {
            String id = "agent-" + ordinal;
            String instruction = ordinal == 1
                    ? "Complete " + scope + " with current connectCenter evidence."
                    : "Independently verify " + scope + " and the other Agent findings.";
            members.add(new AiWorkflowPlan.Member(id,
                    new AiWorkflowPlan.AgentTask(agent.id(), "Agent " + ordinal,
                            instruction,
                            "I’m independently checking the evidence for your request.",
                            null,
                            "Completed",
                            AiWorkflowPlan.ToolAccess.READ_ONLY), null));
        }
        return new AiWorkflowPlan(new AiWorkflowPlan.WorkflowDefinition(
                "planned-workflow", members, List.of()),
                "I’m checking the request from the necessary perspectives.",
                "I’m combining the findings into one answer.");
    }

    private String planningPrompt(AgentWorkflowContext context) {
        return context.assignment() != null
                ? context.assignment().instruction() : context.request().prompt();
    }

    private Optional<Integer> requiredAgentCount(AgentWorkflowContext context) {
        String prompt = planningPrompt(context);
        if (!DelegationIntent.explicitlyRequestsAgents(prompt)) return Optional.empty();
        int requested = DelegationIntent.requestedAgentCount(prompt)
                .orElse(2);
        if (requested > context.request().maximumAgents()) {
            throw new IllegalArgumentException(
                    "Requested Agent count exceeds the current request limit.");
        }
        return Optional.of(requested);
    }

    private String requiredGuide(String value, String label, int maximumLength) {
        String bounded = bounded(value, maximumLength);
        if (!StringUtils.hasText(bounded)) {
            throw new IllegalArgumentException(label + " is required");
        }
        return bounded;
    }

    private ExecutionScope scope(AgentWorkflowContext context) {
        return context.executionScope(ExecutionScope.Purpose.WORKFLOW_PLANNING);
    }

    private List<Map<String, String>> recentConversation(AgentWorkflowContext context) {
        var history = context.execution().history();
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        List<Map<String, String>> result = new ArrayList<>();
        for (int index = from; index < history.size(); index++) {
            var message = history.get(index);
            if (message == null || !StringUtils.hasText(message.content())) continue;
            String text = bounded(message.content(), MAX_HISTORY_MESSAGE_LENGTH);
            String role = switch (message) {
                case AiMessage.System ignored -> "system";
                case AiMessage.User ignored -> "user";
                case AiMessage.Assistant ignored -> "assistant";
                case AiMessage.ToolCall ignoredCall -> "tool";
                case AiMessage.ToolResult ignoredResult -> "tool";
            };
            result.add(Map.of("role", role, "content", text));
        }
        return List.copyOf(result);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialize Planner Agent input.", failure);
        }
    }

    private String bounded(String value, int limit) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= limit
                ? normalized : normalized.substring(0, limit).stripTrailing();
    }
}

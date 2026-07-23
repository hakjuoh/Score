package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowPlanValidator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Selects Agents and nested Workflows without selecting an execution pattern type. */
@Component("workflow-planner")
public final class PlannerAgent extends CatalogBackedAgent implements WorkflowAgent {

    private static final int MAX_INSTRUCTION_LENGTH = 4_000;
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_HISTORY_MESSAGE_LENGTH = 1_500;
    private final AgentExecutionService execution;
    private final SpringAiModelCatalog models;
    private final AiAgentCatalog agents;
    private final ObjectMapper objectMapper;
    private final WorkflowPlanValidator validator = new WorkflowPlanValidator();

    public PlannerAgent(AgentExecutionService execution, SpringAiModelCatalog models,
                        AiAgentCatalog agents, ObjectMapper objectMapper) {
        super(agents);
        this.execution = execution;
        this.models = models;
        this.agents = agents;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentDecision execute(AgentWorkflowContext context) {
        return new AgentDecision.Delegate(plan(context));
    }

    public AiWorkflowPlan plan(AgentWorkflowContext context) {
        AgentWorkflowContext current = context;
        String prompt = definition().instruction().render().value();
        ResolvedAgent resolved = new ResolvedAgent(definition(),
                models.require(current.request().modelName()),
                new Instruction(prompt), ToolSet.empty());
        AgentInvocation invocation = new AgentInvocation(null, resolved,
                new AiMessage.User("UNTRUSTED_PLANNING_INPUT\n"
                        + json(planningParameters(current))
                        + "\nReturn the recursive Workflow JSON for this input."),
                List.of(), scope(current), null, current.observationContext());
        try {
            AgentRunResult result = execution.execute(invocation);
            current.recordUsage(definition().name(), result);
            String raw = result.response().content();
            AiWorkflowPlan parsed = parse(raw);
            validator.validate(parsed, current.request().maximumAgents(), id -> {
                agents.requireWorker(id);
                return true;
            });
            return normalize(parsed);
        } catch (CancellationException | AgentInputRefusedException terminal) {
            throw terminal;
        } catch (RuntimeException failure) {
            current.execution().recorder().lifecycle("workflow_plan_fallback",
                    "The Planner Agent returned an unusable Workflow; using a bounded fallback.",
                    Map.of("status", "fallback", "reason", failure.getClass().getSimpleName()));
            return fallback(current);
        }
    }

    private Map<String, Object> planningParameters(AgentWorkflowContext context) {
        var request = context.request();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("userRequest", request.prompt());
        parameters.put("recentConversation", recentConversation(context));
        parameters.put("hasAttachments", request.hasAttachments());
        parameters.put("hasPageContext", request.hasPageContext());
        parameters.put("maximumAgents", request.maximumAgents());
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

    private AiWorkflowPlan normalize(AiWorkflowPlan plan) {
        if (plan == null || plan.root() == null) {
            throw new IllegalArgumentException("The Planner Agent returned no root Workflow.");
        }
        AiWorkflowPlan.WorkflowDefinition root = normalizeWorkflow(plan.root());
        return new AiWorkflowPlan(root, bounded(plan.guideMessage(), 180),
                bounded(plan.synthesisGuideMessage(), 180));
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
        return new AiWorkflowPlan.WorkflowDefinition(workflow.id(), normalized);
    }

    private AiWorkflowPlan.AgentTask normalizeTask(AiWorkflowPlan.AgentTask task) {
        AiAgentDefinition definition = agents.requireWorker(task.agentId());
        return new AiWorkflowPlan.AgentTask(definition.id(),
                bounded(task.label(), 100), bounded(task.instruction(), MAX_INSTRUCTION_LENGTH),
                bounded(task.guideMessage(), 180), bounded(task.activeVerb(), 32),
                bounded(task.completedVerb(), 32), task.toolAccess());
    }

    private AiWorkflowPlan fallback(AgentWorkflowContext context) {
        AiAgentDefinition agent = agents.defaultAgent();
        int requested = context.request().delegationRequested()
                ? context.request().maximumAgents() : 1;
        List<AiWorkflowPlan.Member> members = new ArrayList<>();
        for (int ordinal = 1; ordinal <= requested; ordinal++) {
            String id = "agent-" + ordinal;
            String instruction = ordinal == 1
                    ? "Complete the user's request with current connectCenter evidence."
                    : "Independently verify the user's request and the other Agent findings.";
            members.add(new AiWorkflowPlan.Member(id,
                    new AiWorkflowPlan.AgentTask(agent.id(), "Agent " + ordinal,
                            instruction, null, "Working", "Completed",
                            AiWorkflowPlan.ToolAccess.READ_ONLY), null));
        }
        return new AiWorkflowPlan(new AiWorkflowPlan.WorkflowDefinition("planned-workflow", members),
                "Delegating the request to the selected Agents.",
                "Combining the Agent results.");
    }

    private ExecutionScope scope(AgentWorkflowContext context) {
        return new ExecutionScope(context.request().requestId(),
                context.request().conversationId(), context.request().requesterId(), 0L,
                ExecutionScope.Purpose.WORKFLOW_PLANNING,
                context.execution().guardrailDecisionIds());
    }

    private List<Map<String, String>> recentConversation(AgentWorkflowContext context) {
        var history = context.execution().history();
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        List<Map<String, String>> result = new ArrayList<>();
        for (int index = from; index < history.size(); index++) {
            var message = history.get(index);
            if (message == null || !StringUtils.hasText(message.getText())) continue;
            String text = bounded(message.getText(), MAX_HISTORY_MESSAGE_LENGTH);
            result.add(Map.of("role", message.getMessageType().name().toLowerCase(Locale.ROOT),
                    "content", text));
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

package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntime;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntimeRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiSystemPrompt;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Uses the selected model to choose the workflow, guide text, verbs, and registered workers. */
@Component
public final class AiWorkflowPlanner {

    private static final String PROMPT = "classpath:prompts/connect-center-workflow-planner-system-prompt.md";
    private static final Set<String> WORKFLOWS = Set.of(
            "direct", "chain", "parallel", "routing", "orchestrator_workers");
    private static final int MAX_GUIDE_LENGTH = 180;
    private static final int MAX_VERB_LENGTH = 32;
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_HISTORY_MESSAGE_LENGTH = 1_500;
    private static final Pattern FOLLOW_UP_TOOL_INTENT = Pattern.compile(
            "(?iu)^\\s*(?:(?:please|also|now|then)\\s+)*(?:"
                    + "add|create|change|update|delete|remove|clean(?:\\s+up)?|proceed|continue|"
                    + "apply|set|assign|link|associate|include|replace|rename|modify|"
                    + "show|list|get|find|search|check|verify|read|retrieve)\\b");
    private final AiRuntimeRegistry runtimes;
    private final AiAgentCatalog agents;
    private final ObjectMapper objectMapper;
    private final ScoreAiSystemPrompt plannerPrompt;

    public AiWorkflowPlanner(AiRuntimeRegistry runtimes, AiAgentCatalog agents,
                             ObjectMapper objectMapper, ResourceLoader resources) {
        this.runtimes = runtimes;
        this.agents = agents;
        this.objectMapper = objectMapper;
        this.plannerPrompt = new ScoreAiSystemPrompt(resources.getResource(PROMPT));
    }

    public AiWorkflowPlan plan(AiRuntime.Context context) {
        AiTrajectoryRecorder recorder = context.recorder().fork(Map.of(
                "node_id", context.request().requestId() + ":workflow-planner",
                "agent_name", "workflow-planner",
                "agent_role", "workflow selection",
                "depth", 0));
        String renderedPrompt = plannerPrompt.render(planningPromptParameters(context));
        AiRuntime.Context planning = new AiRuntime.Context(
                context.request().withMultiAgent(AiMultiAgentOptions.single()),
                List.of(new SystemMessage(renderedPrompt)),
                new UserMessage("Return the workflow plan for the untrusted input above."),
                context.requester(), recorder, false, false, AiRuntime.ToolPolicy.NONE, 0);
        try {
            String raw = runtimes.execute(context.request().runtime(), planning).answer();
            return normalize(parse(raw), context);
        } catch (RuntimeException failure) {
            recorder.lifecycle("workflow_plan_fallback",
                    "The workflow planner returned an unusable plan; using the safe fallback.",
                    Map.of("status", "fallback", "reason", failure.getClass().getSimpleName()));
            return fallback(context);
        }
    }

    private Map<String, Object> planningPromptParameters(AiRuntime.Context context) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("userRequest", json(context.request().prompt()));
        envelope.put("recentConversation", json(recentConversation(context.history())));
        envelope.put("hasAttachments", !context.request().attachments().isEmpty());
        envelope.put("hasPageContext", StringUtils.hasText(context.request().pageContext()));
        envelope.put("approvedMutationContinuation", context.request().mutationConfirmation() != null);
        envelope.put("agentWorkflowsAllowed", agentWorkflowsAllowed(context));
        envelope.put("maximumWorkers", context.request().multiAgent().maxAgents());
        envelope.put("strategyPreference", json(context.request().multiAgent().strategy()));
        envelope.put("activeWorkflow", json(context.request().activeWorkflow()));
        envelope.put("explicitFanOutRequested",
                AiMultiAgentIntent.explicitlyRequestsFanOut(context.request().prompt()));
        envelope.put("explicitAgentWorkflowRequested",
                AiMultiAgentIntent.explicitlyRequestsAgents(context.request().prompt()));
        envelope.put("registeredAgents", json(agents.all().stream().map(agent -> Map.of(
                "id", agent.id(), "name", agent.name(), "description", agent.description())).toList()));
        return Map.copyOf(envelope);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not serialize a workflow planning input.", impossible);
        }
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

    private AiWorkflowPlan normalize(AiWorkflowPlan plan, AiRuntime.Context context) {
        if (plan == null) throw new IllegalArgumentException("Workflow plan is missing.");
        String activeWorkflow = activeWorkflow(context);
        String workflow = activeWorkflow != null
                ? activeWorkflow : normalizedWorkflow(plan.workflow());
        boolean tools = plan.toolsNeeded() || followUpToolIntent(context);
        boolean agentsAllowed = agentWorkflowsAllowed(context);
        boolean agentWorkflowRequired = activeWorkflow != null && !"direct".equals(activeWorkflow);
        boolean explicitFanOut = AiMultiAgentIntent.explicitlyRequestsFanOut(context.request().prompt());
        String rootGuide = guide(plan.guideMessage());
        if ((tools || agentWorkflowRequired) && rootGuide == null) rootGuide = defaultGuide(tools);
        if (!tools && !agentWorkflowRequired) {
            return new AiWorkflowPlan("direct", false, null,
                    verb(plan.activeVerb(), "Answering"), verb(plan.completedVerb(), "Answered"),
                    null, verb(plan.synthesisActiveVerb(), "Answering"),
                    verb(plan.synthesisCompletedVerb(), "Answered"), List.of());
        }

        List<AiWorkflowPlan.Task> tasks = new ArrayList<>();
        if (agentsAllowed) {
            int cap = context.request().multiAgent().maxAgents();
            for (AiWorkflowPlan.Task task : plan.tasks()) {
                if (tasks.size() >= cap || task == null) break;
                AiAgentDefinition definition = agents.require(task.agentId());
                tasks.add(new AiWorkflowPlan.Task(
                        label(task.label(), definition.name()), definition.id(),
                        instruction(task.instruction()),
                        guide(task.guideMessage()) != null ? guide(task.guideMessage()) : rootGuide,
                        verb(task.activeVerb(), "Working"), verb(task.completedVerb(), "Completed")));
            }
        }
        if (agentsAllowed && agentWorkflowRequired && tasks.isEmpty()) {
            AiAgentDefinition fallback = agents.defaultAgent();
            tasks.add(fallbackTask(fallback, false, rootGuide, tools));
        }
        // A model-selected parallel plan is just as authoritative as a forced
        // preference or explicit fan-out request. Never let malformed planner
        // output silently collapse a parallel workflow to one child execution.
        boolean parallelRequired = "parallel".equals(workflow) || explicitFanOut;
        if (agentsAllowed && parallelRequired && tasks.size() < 2) {
            AiAgentDefinition fallback = agents.defaultAgent();
            while (tasks.size() < Math.min(2, context.request().multiAgent().maxAgents())) {
                tasks.add(fallbackTask(fallback, !tasks.isEmpty(), rootGuide, tools));
            }
            workflow = "parallel";
        }
        if ("direct".equals(activeWorkflow)) {
            tasks.clear();
            workflow = "direct";
        } else if ("routing".equals(activeWorkflow) && tasks.size() > 1) {
            tasks = List.of(tasks.getFirst());
        }
        if (tasks.isEmpty() || !agentsAllowed) {
            workflow = "chain".equals(workflow) ? "chain" : "direct";
        } else if (activeWorkflow != null) {
            workflow = activeWorkflow;
        } else if ("direct".equals(workflow)) {
            workflow = tasks.size() > 1 ? "parallel" : "routing";
        } else if ("routing".equals(workflow) && tasks.size() > 1) {
            tasks = List.of(tasks.getFirst());
        }
        return new AiWorkflowPlan(workflow, tools, rootGuide,
                verb(plan.activeVerb(), "Working"), verb(plan.completedVerb(), "Completed"),
                guide(plan.synthesisGuideMessage()),
                verb(plan.synthesisActiveVerb(), "Synthesizing"),
                verb(plan.synthesisCompletedVerb(), "Synthesized"), tasks);
    }

    private AiWorkflowPlan fallback(AiRuntime.Context context) {
        String activeWorkflow = activeWorkflow(context);
        boolean explicitAgents = agentWorkflowsAllowed(context)
                && activeWorkflow != null && !"direct".equals(activeWorkflow);
        boolean fanOut = agentWorkflowsAllowed(context)
                && ("parallel".equals(activeWorkflow)
                || AiMultiAgentIntent.explicitlyRequestsFanOut(context.request().prompt()));
        List<AiWorkflowPlan.Task> tasks = new ArrayList<>();
        if (explicitAgents) {
            AiAgentDefinition agent = agents.defaultAgent();
            int count = fanOut ? Math.min(2, context.request().multiAgent().maxAgents()) : 1;
            for (int ordinal = 1; ordinal <= count; ordinal++) {
                tasks.add(fallbackTask(agent, ordinal > 1,
                        "I’ll review the request with read-only agents.", true));
            }
        }
        String workflow = activeWorkflow != null ? activeWorkflow
                : fanOut ? "parallel" : explicitAgents ? "chain" : "direct";
        return new AiWorkflowPlan(workflow, true,
                "I’ll check the connectCenter information needed to handle this request.",
                fanOut ? "Reviewing" : "Working", fanOut ? "Reviewed" : "Completed",
                "I’ll synthesize the collected results and prepare the answer.",
                "Synthesizing", "Synthesized", tasks);
    }

    private AiWorkflowPlan.Task fallbackTask(AiAgentDefinition agent, boolean verifier,
                                              String rootGuide, boolean tools) {
        String active = verifier ? "Verifying" : "Researching";
        String completed = verifier ? "Verified" : "Researched";
        return new AiWorkflowPlan.Task(
                verifier ? "Independent verification" : tools ? "Current-state evidence" : "Independent analysis",
                agent.id(), verifier
                ? "Independently verify the current records, identifiers, existing state, duplicates, "
                    + "and read-back criteria needed for the user's request. Return evidence only; do not mutate."
                : tools
                ? "Inspect the current connectCenter records and relationships needed to identify the exact "
                    + "target and inputs for the user's request. Return identifiers and evidence only; do not mutate."
                : "Analyze the user's request independently. Return a concise answer or useful evidence to the lead; "
                    + "do not perform mutations.",
                rootGuide, active, completed);
    }

    private String defaultGuide(boolean tools) {
        return tools
                ? "I’ll check the connectCenter information needed to handle this request."
                : "I’ll handle this request with the selected agent workflow.";
    }

    private List<Map<String, String>> recentConversation(List<Message> history) {
        if (history == null || history.isEmpty()) return List.of();
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        List<Map<String, String>> messages = new ArrayList<>(history.size() - from);
        for (int index = from; index < history.size(); index++) {
            Message message = history.get(index);
            if (message == null || !StringUtils.hasText(message.getText())) continue;
            String content = message.getText().strip();
            if (content.length() > MAX_HISTORY_MESSAGE_LENGTH) {
                content = content.substring(0, MAX_HISTORY_MESSAGE_LENGTH).stripTrailing() + "…";
            }
            messages.add(Map.of(
                    "role", message.getMessageType().name().toLowerCase(Locale.ROOT),
                    "content", content));
        }
        return List.copyOf(messages);
    }

    private boolean followUpToolIntent(AiRuntime.Context context) {
        return !context.history().isEmpty()
                && StringUtils.hasText(context.request().prompt())
                && FOLLOW_UP_TOOL_INTENT.matcher(context.request().prompt()).find();
    }

    private boolean agentWorkflowsAllowed(AiRuntime.Context context) {
        return context.request().mutationConfirmation() == null
                && !"direct".equals(activeWorkflow(context));
    }

    private String activeWorkflow(AiRuntime.Context context) {
        return StringUtils.hasText(context.request().activeWorkflow())
                ? normalizedWorkflow(context.request().activeWorkflow()) : null;
    }

    private String normalizedWorkflow(String value) {
        String normalized = StringUtils.hasText(value)
                ? value.strip().toLowerCase(Locale.ROOT).replace('-', '_') : "direct";
        if (!WORKFLOWS.contains(normalized)) {
            throw new IllegalArgumentException("Unknown workflow: " + value);
        }
        return normalized;
    }

    private String guide(String value) {
        if (!StringUtils.hasText(value)) return null;
        String result = value.strip().replaceAll("\\s+", " ");
        return result.length() <= MAX_GUIDE_LENGTH ? result : result.substring(0, MAX_GUIDE_LENGTH).stripTrailing();
    }

    private String verb(String value, String fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        String result = value.strip().replaceAll("[.…]+$", "").replaceAll("\\s+", " ");
        return result.length() <= MAX_VERB_LENGTH ? result : result.substring(0, MAX_VERB_LENGTH).stripTrailing();
    }

    private String label(String value, String fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        String result = value.strip().replaceAll("\\s+", " ");
        return result.length() <= 100 ? result : result.substring(0, 100).stripTrailing();
    }

    private String instruction(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("A workflow task is missing its instruction.");
        }
        String result = value.strip();
        return result.length() <= 4_000 ? result : result.substring(0, 4_000).stripTrailing();
    }
}

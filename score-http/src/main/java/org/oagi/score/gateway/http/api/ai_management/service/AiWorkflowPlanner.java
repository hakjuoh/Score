package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowNode;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.service.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowTypes;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Uses the selected model to choose the workflow, guide text, verbs, and registered workers. */
@Component
public final class AiWorkflowPlanner {

    private static final String PROMPT = "classpath:prompts/connect-center-workflow-planner-system-prompt.md";
    private static final Set<String> WORKFLOWS = WorkflowTypes.ALL;
    private static final int MAX_GUIDE_LENGTH = 180;
    private static final int MAX_VERB_LENGTH = 32;
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_HISTORY_MESSAGE_LENGTH = 1_500;
    private static final int MAX_WORKFLOW_DEPTH = 8;
    private static final int MAX_WORKFLOW_NODES = 32;
    private static final Pattern FOLLOW_UP_TOOL_INTENT = Pattern.compile(
            "(?iu)^\\s*(?:(?:please|also|now|then)\\s+)*(?:"
                    + "add|create|change|update|delete|remove|clean(?:\\s+up)?|proceed|continue|"
                    + "apply|set|assign|link|associate|include|replace|rename|modify|"
                    + "show|list|get|find|search|check|verify|read|retrieve)\\b");
    private final AiChatExecutor executor;
    private final AiAgentCatalog agents;
    private final ObjectMapper objectMapper;
    private final ScoreAiSystemPrompt plannerPrompt;

    public AiWorkflowPlanner(AiChatExecutor executor, AiAgentCatalog agents,
                             ObjectMapper objectMapper, ResourceLoader resources) {
        this.executor = executor;
        this.agents = agents;
        this.objectMapper = objectMapper;
        this.plannerPrompt = new ScoreAiSystemPrompt(resources.getResource(PROMPT));
    }

    public AiWorkflowPlan plan(AiChatExecutor.Context context) {
        return plan(context, List.of());
    }

    public AiWorkflowPlan plan(AiChatExecutor.Context context, List<AiWorkflowFeedback> feedback) {
        AiTrajectoryRecorder recorder = context.recorder().fork(Map.of(
                "node_id", context.request().requestId() + ":workflow-planner",
                "agent_name", "workflow-planner",
                "agent_role", "workflow selection",
                "depth", 0));
        String renderedPrompt = plannerPrompt.render(planningPromptParameters(context, feedback));
        AiChatExecutor.Context planning = new AiChatExecutor.Context(
                context.request().withMultiAgent(AiMultiAgentOptions.single()),
                List.of(new SystemMessage(renderedPrompt)),
                new UserMessage("Return the workflow plan for the untrusted input above."),
                context.requester(), recorder, false, false, AiChatExecutor.ToolPolicy.NONE, 0);
        try {
            String raw = executor.execute(planning).answer();
            return normalize(parse(raw), context);
        } catch (RuntimeException failure) {
            recorder.lifecycle("workflow_plan_fallback",
                    "The workflow planner returned an unusable plan; using the safe fallback.",
                    Map.of("status", "fallback", "reason", failure.getClass().getSimpleName()));
            return fallback(context);
        }
    }

    private Map<String, Object> planningPromptParameters(
            AiChatExecutor.Context context, List<AiWorkflowFeedback> feedback) {
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
        envelope.put("priorWorkflowAttempts", json(feedback != null ? feedback : List.of()));
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

    private AiWorkflowPlan normalize(AiWorkflowPlan plan, AiChatExecutor.Context context) {
        if (plan == null) throw new IllegalArgumentException("Workflow plan is missing.");
        if (plan.root() != null) {
            return normalizeComposedPlan(plan, context);
        }
        String activeWorkflow = activeWorkflow(context);
        String workflow = activeWorkflow != null
                ? activeWorkflow : normalizedWorkflow(plan.workflow());
        boolean tools = plan.toolsNeeded() || followUpToolIntent(context);
        boolean agentsAllowed = agentWorkflowsAllowed(context);
        boolean agentWorkflowRequired = activeWorkflow != null
                && !WorkflowTypes.DIRECT.equals(activeWorkflow);
        boolean explicitFanOut = AiMultiAgentIntent.explicitlyRequestsFanOut(context.request().prompt());
        String rootGuide = guide(plan.guideMessage());
        if ((tools || agentWorkflowRequired) && rootGuide == null) rootGuide = defaultGuide(tools);
        if (!tools && !agentWorkflowRequired) {
            return new AiWorkflowPlan(WorkflowTypes.DIRECT, false, null,
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
        boolean parallelRequired = WorkflowTypes.PARALLEL.equals(workflow) || explicitFanOut;
        if (agentsAllowed && parallelRequired && tasks.size() < 2) {
            AiAgentDefinition fallback = agents.defaultAgent();
            while (tasks.size() < Math.min(2, context.request().multiAgent().maxAgents())) {
                tasks.add(fallbackTask(fallback, !tasks.isEmpty(), rootGuide, tools));
            }
            workflow = WorkflowTypes.PARALLEL;
        }
        if (WorkflowTypes.DIRECT.equals(activeWorkflow)) {
            tasks.clear();
            workflow = WorkflowTypes.DIRECT;
        } else if (WorkflowTypes.ROUTING.equals(activeWorkflow) && tasks.size() > 1) {
            tasks = List.of(tasks.getFirst());
        }
        if (tasks.isEmpty() || !agentsAllowed) {
            workflow = WorkflowTypes.CHAIN.equals(workflow)
                    ? WorkflowTypes.CHAIN : WorkflowTypes.DIRECT;
        } else if (activeWorkflow != null) {
            workflow = activeWorkflow;
        } else if (WorkflowTypes.DIRECT.equals(workflow)) {
            workflow = tasks.size() > 1 ? WorkflowTypes.PARALLEL : WorkflowTypes.ROUTING;
        } else if (WorkflowTypes.ROUTING.equals(workflow) && tasks.size() > 1) {
            tasks = List.of(tasks.getFirst());
        }
        return new AiWorkflowPlan(workflow, tools, rootGuide,
                verb(plan.activeVerb(), "Working"), verb(plan.completedVerb(), "Completed"),
                guide(plan.synthesisGuideMessage()),
                verb(plan.synthesisActiveVerb(), "Synthesizing"),
                verb(plan.synthesisCompletedVerb(), "Synthesized"), tasks);
    }

    private AiWorkflowPlan normalizeComposedPlan(AiWorkflowPlan plan, AiChatExecutor.Context context) {
        String activeWorkflow = activeWorkflow(context);
        AtomicInteger nodeCount = new AtomicInteger();
        AtomicInteger workerCount = new AtomicInteger();
        AiWorkflowNode root = normalizeNode(plan.root(), context, "root", 0,
                nodeCount, workerCount);
        if (activeWorkflow != null && !activeWorkflow.equals(root.workflow())) {
            throw new IllegalArgumentException("The composed plan did not honor the active workflow.");
        }

        boolean tools = plan.toolsNeeded() || needsTools(root) || followUpToolIntent(context);
        root = propagateWorkerTools(root, tools);
        boolean explicitlyDelegated = activeWorkflow != null
                && !WorkflowTypes.DIRECT.equals(activeWorkflow)
                || AiMultiAgentIntent.explicitlyRequestsAgents(context.request().prompt());
        root = collapseRedundantAutomaticEvidenceChain(root, explicitlyDelegated);
        if (!tools && !explicitlyDelegated) {
            root = new AiWorkflowNode("root", WorkflowTypes.DIRECT, false, null,
                    verb(root.activeVerb(), "Answering"),
                    verb(root.completedVerb(), "Answered"),
                    null, "Answering", "Answered", null, null, List.of(), Map.of());
        } else if (tools && !root.toolsNeeded()) {
            root = copyWithTools(root, true);
        }

        String rootGuide = guide(root.guideMessage());
        if ((tools || explicitlyDelegated) && rootGuide == null) {
            rootGuide = defaultGuide(tools);
        }
        return new AiWorkflowPlan(root.workflow(), tools, rootGuide,
                verb(root.activeVerb(), "Working"),
                verb(root.completedVerb(), "Completed"),
                guide(root.synthesisGuideMessage()),
                verb(root.synthesisActiveVerb(), "Synthesizing"),
                verb(root.synthesisCompletedVerb(), "Synthesized"),
                List.of(), root);
    }

    /**
     * A lone evidence worker followed by a fully capable lead is a serial duplicate:
     * it adds a model call without concurrency, while the lead must still verify the
     * same current state before answering or mutating. Keep the shape only when the
     * user selected delegation explicitly; automatic complex investigations remain
     * available through multiple workers or other specialist roles.
     */
    private AiWorkflowNode collapseRedundantAutomaticEvidenceChain(
            AiWorkflowNode root, boolean explicitlyDelegated) {
        if (explicitlyDelegated || !WorkflowTypes.CHAIN.equals(root.workflow())
                || root.children().size() != 2) {
            return root;
        }
        AiWorkflowNode evidence = root.children().getFirst();
        AiWorkflowNode lead = root.children().getLast();
        if (!WorkflowTypes.DIRECT.equals(evidence.workflow()) || evidence.task() == null
                || !"evidence-researcher".equals(evidence.task().agentId())
                || !WorkflowTypes.DIRECT.equals(lead.workflow()) || lead.task() != null) {
            return root;
        }
        return new AiWorkflowNode(root.id(), WorkflowTypes.DIRECT,
                root.toolsNeeded() || lead.toolsNeeded(),
                StringUtils.hasText(evidence.guideMessage())
                        ? evidence.guideMessage() : root.guideMessage(),
                root.activeVerb(), root.completedVerb(),
                null, lead.synthesisActiveVerb(), lead.synthesisCompletedVerb(),
                null, null, List.of(), Map.of());
    }

    private AiWorkflowNode normalizeNode(
            AiWorkflowNode node, AiChatExecutor.Context context, String path, int depth,
            AtomicInteger nodeCount, AtomicInteger workerCount) {
        if (node == null) throw new IllegalArgumentException("A workflow node is missing.");
        if (depth > MAX_WORKFLOW_DEPTH || nodeCount.incrementAndGet() > MAX_WORKFLOW_NODES) {
            throw new IllegalArgumentException("The workflow composition is too large.");
        }
        String workflow = normalizedWorkflow(node.workflow());
        String id = workflowId(node.id(), path);
        AiWorkflowPlan.Task task = normalizeTask(node.task(), context, workerCount);

        List<AiWorkflowNode> children = new ArrayList<>();
        for (int index = 0; index < node.children().size(); index++) {
            children.add(normalizeNode(node.children().get(index), context,
                    path + "." + index, depth + 1, nodeCount, workerCount));
        }
        Map<String, AiWorkflowNode> routes = new LinkedHashMap<>();
        node.routes().forEach((route, child) -> {
            String key = routeKey(route);
            routes.put(key, normalizeNode(child, context, path + "." + key,
                    depth + 1, nodeCount, workerCount));
        });

        String selectedRoute = StringUtils.hasText(node.selectedRoute())
                ? routeKey(node.selectedRoute()) : null;
        switch (workflow) {
            case WorkflowTypes.DIRECT -> {
                if (!children.isEmpty() || !routes.isEmpty()) {
                    throw new IllegalArgumentException("A direct workflow cannot contain child workflows.");
                }
            }
            case WorkflowTypes.CHAIN -> {
                requireChildren(workflow, children, 1);
                requireNoTaskOrRoutes(workflow, task, routes);
            }
            case WorkflowTypes.PARALLEL -> {
                requireChildren(workflow, children, 2);
                requireNoTaskOrRoutes(workflow, task, routes);
                countConcurrentBranches(children, context, workerCount);
            }
            case WorkflowTypes.ORCHESTRATOR_WORKERS -> {
                requireChildren(workflow, children, 1);
                requireNoTaskOrRoutes(workflow, task, routes);
                countConcurrentBranches(children, context, workerCount);
            }
            case WorkflowTypes.ROUTING -> {
                if (task != null || !children.isEmpty() || routes.isEmpty()) {
                    throw new IllegalArgumentException(
                            "A routing workflow requires routes and no task or children.");
                }
                if (selectedRoute == null || !routes.containsKey(selectedRoute)) {
                    throw new IllegalArgumentException("A routing workflow has an invalid selected route.");
                }
            }
            default -> throw new IllegalArgumentException("Unknown workflow: " + workflow);
        }
        return new AiWorkflowNode(id, workflow, node.toolsNeeded(),
                guide(node.guideMessage()), verb(node.activeVerb(), "Working"),
                verb(node.completedVerb(), "Completed"),
                guide(node.synthesisGuideMessage()),
                verb(node.synthesisActiveVerb(), "Synthesizing"),
                verb(node.synthesisCompletedVerb(), "Synthesized"),
                task, selectedRoute, children, routes);
    }

    private AiWorkflowPlan.Task normalizeTask(
            AiWorkflowPlan.Task task, AiChatExecutor.Context context, AtomicInteger workerCount) {
        if (task == null) return null;
        if (!agentWorkflowsAllowed(context)) {
            throw new IllegalArgumentException("Worker workflows are disabled for this request.");
        }
        if (workerCount.incrementAndGet() > context.request().multiAgent().maxAgents()) {
            throw new IllegalArgumentException("The workflow exceeds the worker limit.");
        }
        AiAgentDefinition definition = agents.require(task.agentId());
        return new AiWorkflowPlan.Task(label(task.label(), definition.name()), definition.id(),
                instruction(task.instruction()), guide(task.guideMessage()),
                verb(task.activeVerb(), "Working"), verb(task.completedVerb(), "Completed"));
    }

    /**
     * Every simultaneously executing branch consumes one model execution, whether or
     * not it is a registered worker, so plain branches count against the same limit.
     * Worker leaves are excluded here because {@link #normalizeTask} already counted them.
     */
    private void countConcurrentBranches(
            List<AiWorkflowNode> children, AiChatExecutor.Context context, AtomicInteger workerCount) {
        for (AiWorkflowNode child : children) {
            if (child.task() != null) continue;
            if (workerCount.incrementAndGet() > context.request().multiAgent().maxAgents()) {
                throw new IllegalArgumentException("The workflow exceeds the worker limit.");
            }
        }
    }

    private void requireChildren(String workflow, List<AiWorkflowNode> children, int minimum) {
        if (children.size() < minimum) {
            throw new IllegalArgumentException(
                    "A " + workflow + " workflow requires at least " + minimum + " child workflows.");
        }
    }

    private void requireNoTaskOrRoutes(
            String workflow, AiWorkflowPlan.Task task, Map<String, AiWorkflowNode> routes) {
        if (task != null || !routes.isEmpty()) {
            throw new IllegalArgumentException(
                    "A " + workflow + " workflow accepts children, not a task or routes.");
        }
    }

    private boolean needsTools(AiWorkflowNode node) {
        return node.toolsNeeded()
                || node.children().stream().anyMatch(this::needsTools)
                || node.routes().values().stream().anyMatch(this::needsTools);
    }

    private AiWorkflowNode copyWithTools(AiWorkflowNode node, boolean tools) {
        return new AiWorkflowNode(node.id(), node.workflow(), tools,
                node.guideMessage(), node.activeVerb(), node.completedVerb(),
                node.synthesisGuideMessage(), node.synthesisActiveVerb(),
                node.synthesisCompletedVerb(), node.task(), node.selectedRoute(),
                node.children(), node.routes());
    }

    private AiWorkflowNode propagateWorkerTools(AiWorkflowNode node, boolean tools) {
        List<AiWorkflowNode> children = node.children().stream()
                .map(child -> propagateWorkerTools(child, tools)).toList();
        Map<String, AiWorkflowNode> routes = new LinkedHashMap<>();
        node.routes().forEach((route, child) ->
                routes.put(route, propagateWorkerTools(child, tools)));
        boolean nodeTools = node.toolsNeeded() || tools && node.task() != null;
        return new AiWorkflowNode(node.id(), node.workflow(), nodeTools,
                node.guideMessage(), node.activeVerb(), node.completedVerb(),
                node.synthesisGuideMessage(), node.synthesisActiveVerb(),
                node.synthesisCompletedVerb(), node.task(), node.selectedRoute(),
                children, routes);
    }

    private String workflowId(String value, String fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        String normalized = value.strip().replaceAll("[^A-Za-z0-9_.:-]", "-");
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100);
    }

    private String routeKey(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("A workflow route key is empty.");
        }
        String result = value.strip();
        return result.length() <= 100 ? result : result.substring(0, 100);
    }

    private AiWorkflowPlan fallback(AiChatExecutor.Context context) {
        String activeWorkflow = activeWorkflow(context);
        boolean explicitAgents = agentWorkflowsAllowed(context)
                && activeWorkflow != null && !WorkflowTypes.DIRECT.equals(activeWorkflow);
        boolean fanOut = agentWorkflowsAllowed(context)
                && (WorkflowTypes.PARALLEL.equals(activeWorkflow)
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
                : fanOut ? WorkflowTypes.PARALLEL
                : explicitAgents ? WorkflowTypes.CHAIN : WorkflowTypes.DIRECT;
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

    private boolean followUpToolIntent(AiChatExecutor.Context context) {
        return !context.history().isEmpty()
                && StringUtils.hasText(context.request().prompt())
                && FOLLOW_UP_TOOL_INTENT.matcher(context.request().prompt()).find();
    }

    private boolean agentWorkflowsAllowed(AiChatExecutor.Context context) {
        return context.request().mutationConfirmation() == null
                && !WorkflowTypes.DIRECT.equals(activeWorkflow(context));
    }

    private String activeWorkflow(AiChatExecutor.Context context) {
        return StringUtils.hasText(context.request().activeWorkflow())
                ? normalizedWorkflow(context.request().activeWorkflow()) : null;
    }

    private String normalizedWorkflow(String value) {
        String normalized = StringUtils.hasText(value)
                ? value.strip().toLowerCase(Locale.ROOT).replace('-', '_') : WorkflowTypes.DIRECT;
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

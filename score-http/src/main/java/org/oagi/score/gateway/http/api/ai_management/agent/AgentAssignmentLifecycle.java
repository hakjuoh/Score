package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stable metadata shared by the Runner-owned assignment lifecycle and responses. */
public final class AgentAssignmentLifecycle {

    private AgentAssignmentLifecycle() {
    }

    public static Map<String, Object> namespace(Agent agent, AgentWorkflowContext context,
                                                AiWorkflowPlan.AgentTask task) {
        AgentWorkflowContext.Location location = Objects.requireNonNull(
                context.location(), "Workflow location");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workflow", location.workflowId());
        value.put("node_id", location.nodeId() + ":agent:" + context.assignmentId());
        value.put("parent_node_id", location.nodeId());
        value.put("agent_id", agent.id().value());
        value.put("agent_name", agent.definition().name());
        value.put("task_label", task.label());
        value.put("depth", location.depth() + 1);
        return Map.copyOf(value);
    }

    public static Map<String, Object> metadata(Map<String, Object> namespace, String status) {
        return metadata(namespace, status, Map.of());
    }

    public static Map<String, Object> metadata(Map<String, Object> namespace, String status,
                                               Map<String, Object> additional) {
        Map<String, Object> value = new LinkedHashMap<>(namespace);
        value.put("status", status);
        value.putAll(additional);
        return Map.copyOf(value);
    }

    public static String status(AiWorkflowPlan.AgentTask task, boolean completed) {
        String value = completed ? task.completedVerb() : task.guideMessage();
        if (!StringUtils.hasText(value)) value = completed ? "Completed" : task.activeVerb();
        if (!StringUtils.hasText(value)) value = completed ? "Completed" : "Working";
        String normalized = value.strip();
        return normalized.matches(".*[.!?。！？]$") ? normalized : normalized + ".";
    }
}

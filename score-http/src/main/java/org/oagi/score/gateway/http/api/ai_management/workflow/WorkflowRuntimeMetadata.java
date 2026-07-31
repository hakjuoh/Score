package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowType;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Builds bounded runtime node identities and lifecycle metadata for workflow events. */
final class WorkflowRuntimeMetadata {
    private WorkflowRuntimeMetadata() { }

    static String nodeId(String parentNodeId, String nodeKey) {
        String parent = StringUtils.hasText(parentNodeId) ? parentNodeId.strip() : "workflow";
        String key = StringUtils.hasText(nodeKey) ? nodeKey.strip() : "node";
        String candidate = parent + ":" + key;
        if (candidate.length() <= 240) return candidate;
        return "workflow:" + UUID.nameUUIDFromBytes(candidate.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> namespace(AgentExecutionContext context,
                                         String workflowName, String nodeId,
                                         String parentNodeId, int depth, int members,
                                         AiWorkflowType workflowType) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workflow", workflowName);
        value.put("node_id", nodeId);
        if (parentNodeId != null) value.put("parent_node_id", parentNodeId);
        value.put("depth", depth);
        value.put("member_count", members);
        value.put("workflow_type", workflowType.wireName());
        value.put("request_id", context.requestId());
        return Map.copyOf(value);
    }

    static Map<String, Object> lifecycle(Map<String, Object> namespace,
                                         String status, Map<String, Object> additional) {
        Map<String, Object> value = new LinkedHashMap<>(namespace);
        value.put("status", status);
        value.putAll(additional);
        return Map.copyOf(value);
    }
}

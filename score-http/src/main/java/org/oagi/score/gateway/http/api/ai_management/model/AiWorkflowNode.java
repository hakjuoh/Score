package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive, model-authored workflow specification.
 *
 * <p>A node is either a direct leaf, an ordered list of children, a set of
 * parallel/orchestrated children, or a named routing table. A direct leaf may
 * carry a registered read-only worker assignment.</p>
 */
public record AiWorkflowNode(
        String id,
        String workflow,
        Boolean toolRequired,
        String guideMessage,
        String activeVerb,
        String completedVerb,
        String synthesisGuideMessage,
        String synthesisActiveVerb,
        String synthesisCompletedVerb,
        AiWorkflowPlan.Task task,
        String selectedRoute,
        List<AiWorkflowNode> children,
        Map<String, AiWorkflowNode> routes) {

    public AiWorkflowNode {
        children = children != null ? List.copyOf(children) : List.of();
        routes = routes != null
                ? Map.copyOf(new LinkedHashMap<>(routes)) : Map.of();
    }

    public boolean toolsNeeded() {
        return Boolean.TRUE.equals(toolRequired);
    }
}

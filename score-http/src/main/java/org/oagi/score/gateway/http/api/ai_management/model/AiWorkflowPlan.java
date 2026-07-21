package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.List;

/** Model-authored workflow and presentation contract for one root request. */
public record AiWorkflowPlan(String workflow, Boolean toolRequired, String guideMessage,
                             String activeVerb, String completedVerb,
                             String synthesisGuideMessage, String synthesisActiveVerb,
                             String synthesisCompletedVerb, List<Task> tasks,
                             AiWorkflowNode root) {

    /** Backward-compatible constructor for the original flat planner contract. */
    public AiWorkflowPlan(String workflow, Boolean toolRequired, String guideMessage,
                          String activeVerb, String completedVerb,
                          String synthesisGuideMessage, String synthesisActiveVerb,
                          String synthesisCompletedVerb, List<Task> tasks) {
        this(workflow, toolRequired, guideMessage, activeVerb, completedVerb,
                synthesisGuideMessage, synthesisActiveVerb, synthesisCompletedVerb,
                tasks, null);
    }

    public AiWorkflowPlan {
        tasks = tasks != null ? List.copyOf(tasks) : List.of();
    }

    public boolean toolsNeeded() {
        return Boolean.TRUE.equals(toolRequired);
    }

    public record Task(String label, String agentId, String instruction,
                       String guideMessage, String activeVerb, String completedVerb) {}
}

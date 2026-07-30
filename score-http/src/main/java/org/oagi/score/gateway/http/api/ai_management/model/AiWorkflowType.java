package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** User-interface execution semantics derived from a Workflow dependency graph. */
public enum AiWorkflowType {
    DIRECT("direct"),
    SEQUENTIAL("sequential"),
    PARALLEL("parallel");

    private final String wireName;

    AiWorkflowType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * A graph is parallel when any scheduler layer contains multiple ready members.
     * This includes fan-out/join and other mixed DAGs; nesting remains structural.
     */
    public static AiWorkflowType from(AiWorkflowPlan.WorkflowDefinition workflow) {
        Set<String> pending = new LinkedHashSet<>();
        workflow.members().forEach(member -> pending.add(member.id()));
        Set<String> completed = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            var ready = workflow.readyMembers(pending, completed);
            if (ready.size() > 1) return PARALLEL;
            if (ready.isEmpty()) {
                throw new IllegalArgumentException("Workflow dependency graph has no ready member.");
            }
            String memberId = ready.getFirst().id();
            pending.remove(memberId);
            completed.add(memberId);
        }
        return SEQUENTIAL;
    }
}

package org.oagi.score.gateway.http.api.ai_management.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Typed access to instruction fragments shared by independently executable Agents. */
@Component
public final class AgentInstructions {

    private final AiAgentCatalog catalog;

    public AgentInstructions(AiAgentCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        for (Template template : Template.values()) {
            catalog.workflowInstruction(template.id);
        }
    }

    public Agent.Instruction render(Template template) {
        return catalog.workflowInstruction(template.id).renderStrict();
    }

    public Agent.Instruction render(Template template, Map<String, ?> parameters) {
        return catalog.workflowInstruction(template.id).renderStrict(parameters);
    }

    public enum Template {
        WORKER_FULL("workflow-worker-full"),
        WORKER_RESTRICTED("workflow-worker-restricted"),
        WORKER_PROGRESS("workflow-worker-progress"),
        UPSTREAM_RESULTS("workflow-upstream-results"),
        ORIGINAL_REQUEST_REFERENCE("workflow-original-request-reference"),
        WORKER_ASSIGNMENT("workflow-worker-assignment");

        private final String id;

        Template(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}

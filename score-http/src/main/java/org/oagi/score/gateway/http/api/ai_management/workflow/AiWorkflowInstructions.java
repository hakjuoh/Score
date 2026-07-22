package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Typed access to model-facing Workflow instruction templates in the common catalog. */
@Component
public final class AiWorkflowInstructions {

    private final AiAgentCatalog catalog;

    public AiWorkflowInstructions(AiAgentCatalog catalog) {
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
        REQUIRED_TOOL_RECOVERY("workflow-required-tool-recovery"),
        READ_ONLY_TOOL_RECOVERY("workflow-read-only-tool-recovery"),
        REPLAN_CONTEXT("workflow-replan-context"),
        WORKER_FULL("workflow-worker-full"),
        WORKER_RESTRICTED("workflow-worker-restricted"),
        UPSTREAM_RESULTS("workflow-upstream-results"),
        COMPOSED_SYNTHESIS("workflow-composed-synthesis"),
        ORIGINAL_REQUEST_REFERENCE("workflow-original-request-reference"),
        WORKER_ASSIGNMENT("workflow-worker-assignment"),
        FINAL_SYNTHESIS("workflow-final-synthesis"),
        FALLBACK_TASK("workflow-fallback-task"),
        COMPATIBILITY_WORKER("workflow-compatibility-worker"),
        RESULT_TRUNCATED("workflow-result-truncated");

        private final String id;

        Template(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }
}

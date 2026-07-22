package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Typed access to model-facing instructions used by the chat execution lifecycle. */
@Component
public final class AiExecutionInstructions {

    private final AiAgentCatalog catalog;

    public AiExecutionInstructions(AiAgentCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        for (Template template : Template.values()) {
            catalog.executionInstruction(template.id);
        }
    }

    static AiExecutionInstructions bundled() {
        return new AiExecutionInstructions(new AiAgentCatalog(new DefaultResourceLoader()));
    }

    public Agent.Instruction render(Template template) {
        return catalog.executionInstruction(template.id).renderStrict();
    }

    public Agent.Instruction render(Template template, Map<String, ?> parameters) {
        return catalog.executionInstruction(template.id).renderStrict(parameters);
    }

    public enum Template {
        TEXTUAL_TOOL_CALL_RECOVERY("execution-textual-tool-call-recovery"),
        READ_BACK_CONTINUATION("execution-read-back-continuation"),
        APPROVAL_CONTINUATION("execution-approval-continuation"),
        REVISED_MUTATION_CONTINUATION("execution-revised-mutation-continuation"),
        REQUEST_SCOPED_INPUT("execution-request-scoped-input"),
        UI_ROUTE_MANIFEST_CONTEXT("execution-ui-route-manifest-context");

        private final String id;

        Template(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }
}

package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.springframework.stereotype.Component;

/** Combines untrusted workflow results inside a nested concurrent branch. */
@Component("workflow-synthesizer")
public final class WorkflowSynthesizerAgent extends CatalogBackedAgent {

    public WorkflowSynthesizerAgent(AiAgentCatalog catalog) {
        super(catalog);
    }
}

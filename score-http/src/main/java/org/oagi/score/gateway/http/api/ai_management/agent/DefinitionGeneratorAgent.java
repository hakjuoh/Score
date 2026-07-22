package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.springframework.stereotype.Component;

/** Generates a bounded business definition without Tools. */
@Component("definition-generator")
public final class DefinitionGeneratorAgent extends CatalogBackedAgent {

    public DefinitionGeneratorAgent(AiAgentCatalog catalog) {
        super(catalog);
    }
}

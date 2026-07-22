package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.springframework.stereotype.Component;

/** Suggests a concise object name without Tools. */
@Component("name-suggester")
public final class NameSuggesterAgent extends CatalogBackedAgent {

    public NameSuggesterAgent(AiAgentCatalog catalog) {
        super(catalog);
    }
}

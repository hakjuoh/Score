package org.oagi.score.gateway.http.api.ai_management.agent;

import org.springframework.stereotype.Component;

/** Summarizes conversation request and response into a concise title without Tools. */
@Component("conversation-titler")
public final class ConversationTitlerAgent extends CatalogBackedAgent {

    public ConversationTitlerAgent(AiAgentCatalog catalog) {
        super(catalog);
    }
}

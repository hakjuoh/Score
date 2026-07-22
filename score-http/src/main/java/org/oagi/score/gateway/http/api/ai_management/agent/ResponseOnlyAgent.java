package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.springframework.stereotype.Component;

/** Regenerates a public response from immutable evidence without Tools or side effects. */
@Component("response-only-agent")
public final class ResponseOnlyAgent extends CatalogBackedAgent {

    public ResponseOnlyAgent(AiAgentCatalog agents) {
        super(agents);
    }
}

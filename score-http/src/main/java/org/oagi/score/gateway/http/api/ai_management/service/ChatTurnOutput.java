package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;

import java.util.Map;

/** Workflow result plus the trusted disclosure candidate for the exact same content. */
record ChatTurnOutput(String answer, Map<String, Object> metadata,
                      String retryFeedback, boolean policyRefusal,
                      AgentOutput disclosureCandidate) {

    static ChatTurnOutput disclosable(AgentOutput candidate) {
        return new ChatTurnOutput(candidate.content(), candidate.metadata(),
                null, false, candidate);
    }
}

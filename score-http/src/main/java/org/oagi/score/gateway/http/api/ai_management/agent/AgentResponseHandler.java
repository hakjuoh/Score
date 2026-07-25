package org.oagi.score.gateway.http.api.ai_management.agent;

/** Converts a raw shared-runner result into the next declarative Workflow action. */
@FunctionalInterface
public interface AgentResponseHandler {

    AgentDecision handle(AgentResponseContext response);

    /** Allows an Agent definition to classify failures without owning the run loop. */
    default AgentDecision onFailure(AgentFailure failure) {
        throw failure.exception();
    }

    static AgentResponseHandler complete() {
        return response -> new AgentDecision.Complete(
                new AgentOutput(
                        response.result().response().content(),
                        response.result().metadata().attributes()));
    }
}

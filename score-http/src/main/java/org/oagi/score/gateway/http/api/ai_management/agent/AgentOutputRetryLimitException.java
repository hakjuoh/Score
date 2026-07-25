package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;

/** Raised by the shared Runner when an output policy cannot produce a safe result in bounds. */
public final class AgentOutputRetryLimitException extends AgentGuardrailRefusedException {

    private final String feedback;

    public AgentOutputRetryLimitException(Agent.AgentId agentId, String feedback) {
        super(new GuardrailRefusal(
                GuardrailDecision.of("agent-output-policy", "1",
                        GuardrailDecision.Action.REFUSE),
                "AGENT_OUTPUT_RETRY_LIMIT", "ai.policy.refused"), agentId);
        this.feedback = feedback;
    }

    public String feedback() {
        return feedback;
    }
}

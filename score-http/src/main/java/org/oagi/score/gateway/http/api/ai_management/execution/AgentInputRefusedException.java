package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;

/**
 * @deprecated Use {@link org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException}.
 * Kept as an input-specific compatibility subtype for existing callers.
 */
@Deprecated(forRemoval = false)
public final class AgentInputRefusedException
        extends org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException {

    public AgentInputRefusedException(GuardrailRefusal refusal) {
        this(refusal, null);
    }

    public AgentInputRefusedException(GuardrailRefusal refusal, Agent.AgentId agentId) {
        super(refusal, agentId);
    }

    @Override
    public AgentInputRefusedException identifiedBy(Agent.AgentId value) {
        return agentId().isPresent() ? this : new AgentInputRefusedException(refusal(), value);
    }
}

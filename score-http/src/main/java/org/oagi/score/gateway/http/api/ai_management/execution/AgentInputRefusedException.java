package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;

import java.util.Objects;
import java.util.Optional;

/** Structured, provider-neutral terminal outcome for a refused assembled Agent input. */
public final class AgentInputRefusedException extends RuntimeException {

    private final GuardrailRefusal refusal;
    private final Agent.AgentId agentId;

    public AgentInputRefusedException(GuardrailRefusal refusal) {
        this(refusal, null);
    }

    public AgentInputRefusedException(GuardrailRefusal refusal, Agent.AgentId agentId) {
        super("The assembled Agent input was refused by policy.");
        this.refusal = Objects.requireNonNull(refusal, "refusal");
        this.agentId = agentId;
    }

    public GuardrailRefusal refusal() {
        return refusal;
    }

    public Optional<Agent.AgentId> agentId() {
        return Optional.ofNullable(agentId);
    }

    public AgentInputRefusedException identifiedBy(Agent.AgentId value) {
        return agentId != null ? this : new AgentInputRefusedException(refusal, value);
    }
}

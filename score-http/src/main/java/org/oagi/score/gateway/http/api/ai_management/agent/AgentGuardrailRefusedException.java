package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;

import java.util.Objects;
import java.util.Optional;

/** Structured, provider-neutral terminal outcome for a refused Agent turn. */
public class AgentGuardrailRefusedException extends RuntimeException {

    private final GuardrailRefusal refusal;
    private final Agent.AgentId agentId;

    public AgentGuardrailRefusedException(GuardrailRefusal refusal) {
        this(refusal, null);
    }

    public AgentGuardrailRefusedException(GuardrailRefusal refusal, Agent.AgentId agentId) {
        super("The Agent turn was refused by policy.");
        this.refusal = Objects.requireNonNull(refusal, "refusal");
        this.agentId = agentId;
    }

    public GuardrailRefusal refusal() {
        return refusal;
    }

    public Optional<Agent.AgentId> agentId() {
        return Optional.ofNullable(agentId);
    }

    public AgentGuardrailRefusedException identifiedBy(Agent.AgentId value) {
        return agentId != null ? this : new AgentGuardrailRefusedException(refusal, value);
    }
}

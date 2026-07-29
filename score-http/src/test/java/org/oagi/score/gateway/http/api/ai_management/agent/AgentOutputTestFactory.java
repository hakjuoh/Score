package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;

import java.util.Map;

/** Test-only issuer for opaque output-policy evidence. */
public final class AgentOutputTestFactory {

    private AgentOutputTestFactory() {
    }

    public static AgentOutput publicOutput(String content) {
        return AgentOutput.policyChecked(content, Map.of(), AgentOutputGuardrail.Scope.PUBLIC);
    }
}

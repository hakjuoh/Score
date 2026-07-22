package org.oagi.score.gateway.http.api.ai_management.guardrail;

import java.util.Objects;

/** Internal refusal reference; public text is selected by application-owned message key. */
public record GuardrailRefusal(GuardrailDecision decision, String policyCode,
                               String publicMessageKey) {
    public GuardrailRefusal {
        Objects.requireNonNull(decision, "decision");
        policyCode = required(policyCode, "policyCode");
        publicMessageKey = required(publicMessageKey, "publicMessageKey");
        if (decision.action() != GuardrailDecision.Action.REFUSE) {
            throw new IllegalArgumentException("A refusal requires a REFUSE decision.");
        }
    }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }
}

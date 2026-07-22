package org.oagi.score.gateway.http.api.ai_management.guardrail;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe decision metadata. It deliberately excludes rejected content and classifier rationale. */
public record GuardrailDecision(String decisionId, String policyId, String policyVersion,
                                Action action, RetentionDirective retention, Instant decidedAt) {
    public GuardrailDecision {
        decisionId = required(decisionId, "decisionId");
        policyId = required(policyId, "policyId");
        policyVersion = required(policyVersion, "policyVersion");
        action = Objects.requireNonNull(action, "action");
        retention = retention != null ? retention : RetentionDirective.METADATA_ONLY;
        decidedAt = decidedAt != null ? decidedAt : Instant.now();
    }

    public static GuardrailDecision of(String policyId, String version, Action action) {
        return new GuardrailDecision(UUID.randomUUID().toString(), policyId, version, action,
                RetentionDirective.METADATA_ONLY, Instant.now());
    }

    public enum Action { ALLOW, REWRITE, RETRY, REFUSE }
    public enum RetentionDirective { NONE, METADATA_ONLY, SAFE_REWRITE, FULL }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }
}

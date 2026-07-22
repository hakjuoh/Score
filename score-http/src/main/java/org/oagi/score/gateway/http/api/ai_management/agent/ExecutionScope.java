package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.List;
import java.util.Objects;

/** Trusted, immutable correlation and policy context propagated to every derived Agent run. */
public record ExecutionScope(String requestId, String conversationId, String requesterId,
                             long generation, Purpose purpose,
                             List<String> guardrailDecisionIds) {

    public ExecutionScope {
        requestId = required(requestId, "requestId");
        conversationId = required(conversationId, "conversationId");
        requesterId = required(requesterId, "requesterId");
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        purpose = Objects.requireNonNull(purpose, "purpose");
        guardrailDecisionIds = guardrailDecisionIds != null
                ? guardrailDecisionIds.stream().filter(Objects::nonNull).distinct().toList() : List.of();
    }

    public ExecutionScope withPurpose(Purpose trustedPurpose) {
        return new ExecutionScope(requestId, conversationId, requesterId, generation,
                trustedPurpose, guardrailDecisionIds);
    }

    public ExecutionScope withDecision(String decisionId) {
        var decisions = new java.util.ArrayList<>(guardrailDecisionIds);
        decisions.add(required(decisionId, "decisionId"));
        return new ExecutionScope(requestId, conversationId, requesterId, generation, purpose, decisions);
    }

    public enum Purpose {
        USER_RESPONSE, GATEWAY_ROUTING, GUARDRAIL_EVALUATION, WORKFLOW_PLANNING,
        WORKER, EVALUATION, SYNTHESIS, COMPACTION, RESPONSE_ONLY_RETRY
    }

    private static String required(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }
}

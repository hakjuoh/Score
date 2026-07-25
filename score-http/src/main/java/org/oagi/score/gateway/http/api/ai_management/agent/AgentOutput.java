package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;

import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral output interpreted by a Workflow after an Agent run.
 *
 * <p>Output-policy evidence is deliberately not part of the public constructors or
 * metadata. Only the package-local policy engine can issue it after evaluating the
 * final content. Callers may therefore use metadata for diagnostics, but never as
 * proof that content is safe for public disclosure.</p>
 */
public final class AgentOutput {

    public static final String OUTPUT_GUARDRAIL_APPLIED = "agent_output_guardrail_applied";
    public static final String OUTPUT_GUARDRAIL_SCOPE = "agent_output_guardrail_scope";

    private final String content;
    private final Map<String, Object> metadata;
    private final PolicyEvidence policyEvidence;

    public AgentOutput(String content) {
        this(content, Map.of());
    }

    public AgentOutput(String content, Map<String, Object> metadata) {
        this(content, metadata, PolicyEvidence.NONE);
    }

    private AgentOutput(String content, Map<String, Object> metadata,
                        PolicyEvidence policyEvidence) {
        this.content = Objects.requireNonNullElse(content, "");
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.policyEvidence = Objects.requireNonNull(policyEvidence, "policyEvidence");
    }

    static AgentOutput policyChecked(String content, Map<String, Object> metadata,
                                     AgentOutputGuardrail.Scope scope) {
        return new AgentOutput(content, metadata,
                scope == AgentOutputGuardrail.Scope.PUBLIC
                        ? PolicyEvidence.PUBLIC : PolicyEvidence.INTERNAL);
    }

    public String content() {
        return content;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    /** Returns true only for evidence issued for this exact final content. */
    public boolean passedOutputGuardrail(AgentOutputGuardrail.Scope requiredScope) {
        Objects.requireNonNull(requiredScope, "requiredScope");
        return policyEvidence == PolicyEvidence.PUBLIC
                || requiredScope == AgentOutputGuardrail.Scope.INTERNAL
                && policyEvidence == PolicyEvidence.INTERNAL;
    }

    /** Adds trusted orchestration metadata without changing the evaluated content. */
    public AgentOutput withMetadata(Map<String, Object> value) {
        return new AgentOutput(content, value, policyEvidence);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof AgentOutput that
                && content.equals(that.content) && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, metadata);
    }

    @Override
    public String toString() {
        return "AgentOutput[content=" + content + ", metadata=" + metadata + "]";
    }

    private enum PolicyEvidence {
        NONE, INTERNAL, PUBLIC
    }
}

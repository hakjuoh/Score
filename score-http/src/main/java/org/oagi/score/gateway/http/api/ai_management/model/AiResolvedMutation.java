package org.oagi.score.gateway.http.api.ai_management.model;

/** Tool-protocol result injected when an approval decision resumes the same agent turn. */
public record AiResolvedMutation(
        String toolName,
        String arguments,
        String result,
        boolean executed) {
}

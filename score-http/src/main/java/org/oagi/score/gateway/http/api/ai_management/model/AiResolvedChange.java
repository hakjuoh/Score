package org.oagi.score.gateway.http.api.ai_management.model;

/** Tool-protocol result injected when an approval decision resumes the same agent turn. */
public record AiResolvedChange(
        String toolName,
        String arguments,
        String result,
        boolean executed) {
}

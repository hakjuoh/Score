package org.oagi.score.gateway.http.api.ai_management.model;

/** Bounded tool output and byte-size metadata. */
public record AiBoundedToolOutput(
        String value,
        boolean truncated,
        int originalBytes,
        int returnedBytes) {
}

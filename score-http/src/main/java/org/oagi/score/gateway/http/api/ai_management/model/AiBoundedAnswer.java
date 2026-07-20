package org.oagi.score.gateway.http.api.ai_management.model;

/** UTF-8 bounded worker answer and size metadata. */
public record AiBoundedAnswer(
        String value,
        boolean truncated,
        int originalUtf8Bytes,
        int returnedUtf8Bytes) {
}

package org.oagi.score.gateway.http.api.ai_management.artifact;

import java.time.Instant;

/** Complete server-side artifact metadata, including its opaque storage locator. */
public record AiArtifactRecord(String artifactId, String conversationId, String requestId,
                               String format, String filename, String mediaType, long size,
                               String sha256, String storageProvider, String storageLocation,
                               Instant createdAt, Instant expiresAt) {

    public AiArtifactDescriptor descriptor() {
        return new AiArtifactDescriptor(artifactId, format, filename, mediaType, size, sha256,
                createdAt, expiresAt, "/api/ai/chat/conversations/" + conversationId
                + "/artifacts/" + artifactId);
    }
}

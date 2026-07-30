package org.oagi.score.gateway.http.api.ai_management.artifact;

import java.time.Instant;

/** Public, storage-neutral description of a generated Assistant artifact. */
public record AiArtifactDescriptor(String artifactId, String format, String filename,
                                   String mediaType, long size, String sha256,
                                   Instant createdAt, Instant expiresAt,
                                   String downloadUrl) {
}

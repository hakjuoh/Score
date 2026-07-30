package org.oagi.score.gateway.http.api.ai_management.file;

import java.time.Instant;

/** Public, storage-neutral description of a generated Assistant file. */
public record AiFileDescriptor(String fileId, String format, String filename,
                                   String mediaType, long size, String sha256,
                                   Instant createdAt, Instant expiresAt,
                                   String downloadUrl) {
}

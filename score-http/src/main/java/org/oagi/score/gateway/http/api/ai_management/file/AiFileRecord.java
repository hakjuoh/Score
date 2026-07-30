package org.oagi.score.gateway.http.api.ai_management.file;

import java.time.Instant;

/** Complete server-side file metadata, including its opaque storage locator. */
public record AiFileRecord(String fileId, String conversationId, String requestId,
                               String format, String filename, String mediaType, long size,
                               String sha256, String storageProvider, String storageLocation,
                               Instant createdAt, Instant expiresAt) {

    public AiFileDescriptor descriptor() {
        return new AiFileDescriptor(fileId, format, filename, mediaType, size, sha256,
                createdAt, expiresAt, "/api/ai/chat/conversations/" + conversationId
                + "/files/" + fileId);
    }
}

package org.oagi.score.gateway.http.api.ai_management.model;

import io.modelcontextprotocol.spec.McpSchema;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** Pending MCP elicitation correlated to an AI request. */
public record AiElicitationPending(
        String elicitationId,
        String appUserId,
        String conversationId,
        String requestId,
        CompletableFuture<McpSchema.ElicitResult> response,
        Instant expiresAt) {
}

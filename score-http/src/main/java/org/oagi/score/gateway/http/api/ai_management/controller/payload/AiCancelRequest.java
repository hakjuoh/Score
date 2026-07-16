package org.oagi.score.gateway.http.api.ai_management.controller.payload;

public record AiCancelRequest(String requestId, String cancellationRequestId,
                              String conversationId, Long expectedGeneration) {}

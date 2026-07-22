package org.oagi.score.gateway.http.api.ai_management.controller.payload;

public record AiConversationModelUpdateRequest(String modelName, String reasoningEffort) {}

package org.oagi.score.gateway.http.api.ai_management.model;

/** Immutable snapshot of the model settings applied to an AI chat conversation. */
public record AiChatConversationSettings(String modelName, String reasoningEffort) {}

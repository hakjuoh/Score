package org.oagi.score.gateway.http.api.ai_management.model;

import org.oagi.score.gateway.http.api.ai_management.service.AiChatExecutor;

/** One registered worker type with its own prompt and tool boundary. */
public record AiAgentDefinition(String id, String name, String description,
                                String prompt, AiChatExecutor.ToolPolicy toolPolicy) {
}

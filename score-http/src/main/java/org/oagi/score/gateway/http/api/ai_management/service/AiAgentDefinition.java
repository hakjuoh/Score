package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntime;

/** One registered worker type with its own prompt and tool boundary. */
public record AiAgentDefinition(String id, String name, String description,
                                String prompt, AiRuntime.ToolPolicy toolPolicy) {
}

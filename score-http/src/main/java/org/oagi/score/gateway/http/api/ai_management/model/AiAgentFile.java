package org.oagi.score.gateway.http.api.ai_management.model;

/** Parsed agent descriptor and prompt body. */
public record AiAgentFile(AiAgentDescriptor descriptor, String prompt) {
}

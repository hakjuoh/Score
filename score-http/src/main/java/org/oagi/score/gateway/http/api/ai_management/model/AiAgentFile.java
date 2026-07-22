package org.oagi.score.gateway.http.api.ai_management.model;

/** Parsed Agent descriptor and instruction body. */
public record AiAgentFile(AiAgentDescriptor descriptor, String instruction) {
}

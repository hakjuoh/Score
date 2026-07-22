package org.oagi.score.gateway.http.api.ai_management.model;

/** Parsed Agent identity frontmatter. Execution capabilities are resolved at runtime. */
public record AiAgentDescriptor(String id, String name, String description) {
}

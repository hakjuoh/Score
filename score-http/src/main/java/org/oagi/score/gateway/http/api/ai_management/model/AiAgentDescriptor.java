package org.oagi.score.gateway.http.api.ai_management.model;

/** Parsed agent frontmatter. */
public record AiAgentDescriptor(String id, String name, String description, String toolPolicy) {
}

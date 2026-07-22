package org.oagi.score.gateway.http.api.ai_management.model;

/** One resource-registered Agent identity and instruction. */
public record AiAgentDefinition(String id, String name, String description,
                                String instruction) {
}

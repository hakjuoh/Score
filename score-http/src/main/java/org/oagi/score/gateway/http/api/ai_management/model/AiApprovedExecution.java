package org.oagi.score.gateway.http.api.ai_management.model;

/** Mutation tool execution completed under an approved grant. */
public record AiApprovedExecution(String toolName, String arguments, String result) {
}

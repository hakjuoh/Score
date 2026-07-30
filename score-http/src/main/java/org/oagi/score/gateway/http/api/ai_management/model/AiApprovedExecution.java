package org.oagi.score.gateway.http.api.ai_management.model;

/** Change tool execution completed under an approved grant. */
public record AiApprovedExecution(String toolName, String arguments, String result) {
}

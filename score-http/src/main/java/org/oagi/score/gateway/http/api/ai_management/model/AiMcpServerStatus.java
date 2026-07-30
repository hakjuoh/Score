package org.oagi.score.gateway.http.api.ai_management.model;

/** Requester-scoped result of initializing one configured MCP server. */
public record AiMcpServerStatus(String name, ConnectionState status, int toolCount) {

    public enum ConnectionState {
        CONNECTED,
        NOT_CONFIGURED,
        UNAVAILABLE
    }
}

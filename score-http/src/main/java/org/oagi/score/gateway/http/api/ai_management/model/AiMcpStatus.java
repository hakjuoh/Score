package org.oagi.score.gateway.http.api.ai_management.model;

import java.util.List;

/** Status of every MCP server entry in configured declaration order. */
public record AiMcpStatus(List<AiMcpServerStatus> servers) {

    public AiMcpStatus {
        servers = servers != null ? List.copyOf(servers) : List.of();
    }
}

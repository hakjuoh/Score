package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** User response to an MCP form elicitation shown inside the AI chat panel. */
public record AiElicitationDecisionRequest(
        String requestId,
        String conversationId,
        String elicitationId,
        String action,
        Map<String, Object> content) {

    public AiElicitationDecisionRequest {
        content = content != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(content)) : Map.of();
    }
}

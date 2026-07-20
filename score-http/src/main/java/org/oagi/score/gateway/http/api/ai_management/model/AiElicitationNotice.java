package org.oagi.score.gateway.http.api.ai_management.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** User-facing request for MCP form elicitation. */
public record AiElicitationNotice(
        String elicitationId,
        String requestId,
        String conversationId,
        String message,
        Map<String, Object> requestedSchema,
        Instant expiresAt) {

    public AiElicitationNotice {
        requestedSchema = requestedSchema != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(requestedSchema)) : Map.of();
    }
}

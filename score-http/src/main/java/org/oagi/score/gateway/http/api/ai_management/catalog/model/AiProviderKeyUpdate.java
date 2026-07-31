package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiProviderKeyUpdate(long expectedVersion,
                                  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
                                  String apiKey,
                                  String reason) {
    @Override
    public String toString() {
        return "AiProviderKeyUpdate[expectedVersion=" + expectedVersion
                + ", apiKey=<redacted>, reason=" + reason + "]";
    }
}

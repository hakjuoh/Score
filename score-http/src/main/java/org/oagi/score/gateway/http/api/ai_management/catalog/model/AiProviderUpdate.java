package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiProviderUpdate(String providerName, String providerType,
                               String baseUrl, String messagesUrl, String apiVersion,
                               boolean enabled,
                               @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String apiKey) {
    /** A pasted key often carries a trailing line break or spaces; they are never part of the key. */
    public AiProviderUpdate {
        apiKey = apiKey == null ? null : apiKey.strip();
    }

    @Override
    public String toString() {
        return "AiProviderUpdate[providerName=" + providerName + ", providerType=" + providerType
                + ", enabled=" + enabled + ", apiKey=<redacted>]";
    }
}

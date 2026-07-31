package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiProviderUpdate(Long expectedVersion, String providerName, String providerType,
                               String baseUrl, String messagesUrl, String anthropicVersion,
                               String apiVersion, boolean enabled,
                               @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String apiKey) {
    @Override
    public String toString() {
        return "AiProviderUpdate[expectedVersion=" + expectedVersion
                + ", providerName=" + providerName + ", providerType=" + providerType
                + ", enabled=" + enabled + ", apiKey=<redacted>]";
    }
}

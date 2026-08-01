package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import java.time.Instant;

public record AiProviderView(AiProviderId aiProviderId, String providerName, String providerType,
                             String baseUrl, String messagesUrl, String apiVersion,
                             boolean enabled, boolean apiKeyConfigured,
                             String updaterLoginId, Instant lastUpdatedAt) {
}

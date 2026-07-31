package org.oagi.score.gateway.http.api.ai_management.catalog.model;

public record AiProviderView(long aiProviderId, String providerName, String providerType,
                             String baseUrl, String messagesUrl, String anthropicVersion,
                             String apiVersion, boolean enabled, boolean apiKeyConfigured,
                             long catalogVersion) {
}

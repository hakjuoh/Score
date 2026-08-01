package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import java.util.Locale;

public enum AiProviderType {
    ANTHROPIC("anthropic"),
    OPENAI("openai");

    private final String value;

    AiProviderType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AiProviderType from(String providerType) {
        String normalized = providerType != null
                ? providerType.strip().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "anthropic" -> ANTHROPIC;
            case "openai" -> OPENAI;
            default -> throw new IllegalArgumentException(
                    "Unsupported AI provider type: " + providerType);
        };
    }
}

package org.oagi.score.gateway.http.api.ai_management.catalog.model;

/** API-key field value for an explicitly requested masked or revealed credential view. */
public record AiProviderApiKeyView(String value, boolean revealed) {

    @Override
    public String toString() {
        return "AiProviderApiKeyView[value=REDACTED, revealed=" + revealed + "]";
    }
}

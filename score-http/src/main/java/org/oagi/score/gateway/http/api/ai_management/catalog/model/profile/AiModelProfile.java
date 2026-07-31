package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

/** Common identity and token limits shared by every provider model. */
public interface AiModelProfile {
    String getProviderType();
    String getModelKey();
    String getProviderModelName();
    String getDisplayName();
    String getDescription();
    ModelTokenConstraints getTokenConstraints();
}

package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

/** Common identity and token limits shared by every provider model. */
public interface AiModelProfile {
    String getProviderType();
    String getModelKey();
    String getProviderModelName();
    String getDisplayName();
    String getDescription();
    ModelTokenConstraints getTokenConstraints();
    List<AiModelOption> getOptions();

    default boolean isChatCompletionsCompatible() { return true; }
}

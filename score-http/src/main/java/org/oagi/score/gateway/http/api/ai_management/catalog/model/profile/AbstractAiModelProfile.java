package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

abstract class AbstractAiModelProfile implements AiModelProfile {
    private final String modelKey;
    private final String providerModelName;
    private final String displayName;
    private final String description;
    private final List<AiModelOption> options;

    protected AbstractAiModelProfile(String modelKey, String providerModelName,
                                     String displayName, String description) {
        this.modelKey = modelKey;
        this.providerModelName = providerModelName;
        this.displayName = displayName;
        this.description = description;
        this.options = List.of();
    }

    protected AbstractAiModelProfile(String modelKey, String providerModelName,
                                     String displayName, String description,
                                     List<AiModelOption> options) {
        this.modelKey = modelKey;
        this.providerModelName = providerModelName;
        this.displayName = displayName;
        this.description = description;
        this.options = List.copyOf(options);
    }

    @Override public final String getModelKey() { return modelKey; }
    @Override public final String getProviderModelName() { return providerModelName; }
    @Override public final String getDisplayName() { return displayName; }
    @Override public final String getDescription() { return description; }
    @Override public final List<AiModelOption> getOptions() { return options; }
}

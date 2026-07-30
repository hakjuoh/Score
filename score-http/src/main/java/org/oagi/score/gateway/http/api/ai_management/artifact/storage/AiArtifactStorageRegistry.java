package org.oagi.score.gateway.http.api.ai_management.artifact.storage;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class AiArtifactStorageRegistry {

    private final ScoreAiProperties properties;
    private final Map<String, AiArtifactStorage> providers;

    public AiArtifactStorageRegistry(ScoreAiProperties properties,
                                     List<AiArtifactStorage> installed) {
        this.properties = properties;
        Map<String, AiArtifactStorage> indexed = new LinkedHashMap<>();
        for (AiArtifactStorage provider : installed != null ? installed : List.<AiArtifactStorage>of()) {
            AiArtifactStorage previous = indexed.put(normalize(provider.id()), provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate artifact storage provider: " + provider.id());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public AiArtifactStorage selected() {
        String configured = properties.getTools().getArtifacts().getStorage().getProvider();
        AiArtifactStorage provider = providers.get(normalize(configured));
        if (provider == null) {
            throw new IllegalStateException("Unknown artifact storage provider '" + configured
                    + "'. Installed providers: " + String.join(", ", providers.keySet()));
        }
        return provider;
    }

    public AiArtifactStorage require(String id) {
        AiArtifactStorage provider = providers.get(normalize(id));
        if (provider == null) throw new IllegalStateException("Artifact storage provider is unavailable: " + id);
        return provider;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("Artifact storage provider is required.");
        return value.strip().toLowerCase(Locale.ROOT);
    }
}

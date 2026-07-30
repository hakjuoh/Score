package org.oagi.score.gateway.http.api.ai_management.tool.file.storage;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class AiFileStorageRegistry {

    private final ScoreAiProperties properties;
    private final Map<String, AiFileStorage> providers;

    public AiFileStorageRegistry(ScoreAiProperties properties,
                                     List<AiFileStorage> installed) {
        this.properties = properties;
        Map<String, AiFileStorage> indexed = new LinkedHashMap<>();
        for (AiFileStorage provider : installed != null ? installed : List.<AiFileStorage>of()) {
            AiFileStorage previous = indexed.put(normalize(provider.id()), provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate file storage provider: " + provider.id());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public AiFileStorage selected() {
        String configured = properties.getTools().getFiles().getStorage().getProvider();
        AiFileStorage provider = providers.get(normalize(configured));
        if (provider == null) {
            throw new IllegalStateException("Unknown file storage provider '" + configured
                    + "'. Installed providers: " + String.join(", ", providers.keySet()));
        }
        return provider;
    }

    public AiFileStorage require(String id) {
        AiFileStorage provider = providers.get(normalize(id));
        if (provider == null) throw new IllegalStateException("File storage provider is unavailable: " + id);
        return provider;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("File storage provider is required.");
        return value.strip().toLowerCase(Locale.ROOT);
    }
}

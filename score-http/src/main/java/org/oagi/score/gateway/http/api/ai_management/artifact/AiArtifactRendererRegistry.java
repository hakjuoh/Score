package org.oagi.score.gateway.http.api.ai_management.artifact;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Extensible registry of artifact renderers keyed by stable format identifiers and aliases. */
@Component
public final class AiArtifactRendererRegistry {

    private final Map<String, AiArtifactRenderer> renderers;

    public AiArtifactRendererRegistry(List<AiArtifactRenderer> installed) {
        Map<String, AiArtifactRenderer> indexed = new LinkedHashMap<>();
        for (AiArtifactRenderer renderer : installed != null ? installed : List.<AiArtifactRenderer>of()) {
            register(indexed, renderer.format(), renderer);
            for (String alias : renderer.aliases()) register(indexed, alias, renderer);
        }
        this.renderers = Map.copyOf(indexed);
    }

    public AiArtifactRenderer require(String format) {
        AiArtifactRenderer renderer = renderers.get(normalize(format));
        if (renderer == null) {
            throw new IllegalArgumentException("Unsupported artifact format: " + format
                    + ". Available formats: " + String.join(", ", formats()));
        }
        return renderer;
    }

    public Set<String> formats() {
        return renderers.values().stream().map(AiArtifactRenderer::format)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void register(Map<String, AiArtifactRenderer> indexed, String key,
                          AiArtifactRenderer renderer) {
        String normalized = normalize(key);
        AiArtifactRenderer previous = indexed.put(normalized, renderer);
        if (previous != null && previous != renderer) {
            throw new IllegalStateException("Duplicate artifact renderer alias: " + key);
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("Artifact format is required.");
        return value.strip().toLowerCase(Locale.ROOT);
    }
}

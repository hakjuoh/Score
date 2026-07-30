package org.oagi.score.gateway.http.api.ai_management.tool.file;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Extensible registry of file renderers keyed by stable format identifiers and aliases. */
@Component
public final class AiFileRendererRegistry {

    private final Map<String, AiFileRenderer> renderers;

    public AiFileRendererRegistry(List<AiFileRenderer> installed) {
        Map<String, AiFileRenderer> indexed = new LinkedHashMap<>();
        for (AiFileRenderer renderer : installed != null ? installed : List.<AiFileRenderer>of()) {
            register(indexed, renderer.format(), renderer);
            for (String alias : renderer.aliases()) register(indexed, alias, renderer);
        }
        this.renderers = Map.copyOf(indexed);
    }

    public AiFileRenderer require(String format) {
        AiFileRenderer renderer = renderers.get(normalize(format));
        if (renderer == null) {
            throw new IllegalArgumentException("Unsupported file format: " + format
                    + ". Available formats: " + String.join(", ", formats()));
        }
        return renderer;
    }

    public Set<String> formats() {
        return renderers.values().stream().map(AiFileRenderer::format)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void register(Map<String, AiFileRenderer> indexed, String key,
                          AiFileRenderer renderer) {
        String normalized = normalize(key);
        AiFileRenderer previous = indexed.put(normalized, renderer);
        if (previous != null && previous != renderer) {
            throw new IllegalStateException("Duplicate file renderer alias: " + key);
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("File format is required.");
        return value.strip().toLowerCase(Locale.ROOT);
    }
}

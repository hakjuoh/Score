package org.oagi.score.gateway.http.api.ai_management.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Component
public final class MarkdownArtifactRenderer implements AiArtifactRenderer {

    @Override public String format() { return "markdown"; }
    @Override public Set<String> aliases() { return Set.of("md", "text/markdown"); }
    @Override public String defaultExtension() { return "md"; }

    @Override
    public RenderedArtifact render(JsonNode content, Map<String, Object> options) {
        if (content == null || !content.isTextual() || content.textValue().isBlank()) {
            throw new IllegalArgumentException("Markdown artifacts require non-empty textual content.");
        }
        return new RenderedArtifact(content.textValue().getBytes(StandardCharsets.UTF_8),
                "text/markdown;charset=UTF-8");
    }
}

package org.oagi.score.gateway.http.api.ai_management.artifact;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/** Converts a format-neutral Tool payload into one downloadable artifact. */
public interface AiArtifactRenderer {

    String format();

    Set<String> aliases();

    String defaultExtension();

    RenderedArtifact render(JsonNode content, Map<String, Object> options);

    record RenderedArtifact(byte[] content, String mediaType) {
        public RenderedArtifact {
            content = content != null ? content.clone() : new byte[0];
            if (mediaType == null || mediaType.isBlank()) {
                throw new IllegalArgumentException("Artifact media type is required.");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}

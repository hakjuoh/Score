package org.oagi.score.gateway.http.api.ai_management.file;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/** Converts a format-neutral Tool payload into one downloadable file. */
public interface AiFileRenderer {

    String format();

    Set<String> aliases();

    String defaultExtension();

    RenderedFile render(JsonNode content, Map<String, Object> options);

    record RenderedFile(byte[] content, String mediaType) {
        public RenderedFile {
            content = content != null ? content.clone() : new byte[0];
            if (mediaType == null || mediaType.isBlank()) {
                throw new IllegalArgumentException("File media type is required.");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}

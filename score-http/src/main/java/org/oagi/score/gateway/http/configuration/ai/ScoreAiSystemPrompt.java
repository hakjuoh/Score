package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reads the assistant system prompt from a configurable classpath or filesystem resource. */
public final class ScoreAiSystemPrompt {

    private final Resource resource;

    public ScoreAiSystemPrompt(Resource resource) {
        this.resource = Objects.requireNonNull(resource, "resource");
        text();
    }

    /**
     * Reads on every call so an externally mounted prompt can be updated without rebuilding the image.
     */
    public String text() {
        try (InputStream input = resource.getInputStream()) {
            String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(prompt)) {
                throw new IllegalStateException(
                        "AI assistant system prompt is empty: " + resource.getDescription());
            }
            return prompt;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read AI assistant system prompt: " + resource.getDescription(), e);
        }
    }
}

package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the assistant system prompt from a configurable classpath or filesystem resource. */
public final class ScoreAiSystemPrompt {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]+)}");
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

    /** Renders Spring-style ${name} placeholders without interpreting placeholders inside values. */
    public String render(Map<String, ?> parameters) {
        return render(text(), parameters);
    }

    private String render(String template, Map<String, ?> parameters) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = parameters.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Missing AI system prompt parameter: " + name);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value.toString()));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}

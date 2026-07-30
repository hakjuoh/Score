package org.oagi.score.gateway.http.api.ai_management.artifact;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects explicit user requests for downloadable output without coupling chat to concrete formats. */
@Component
public final class AiArtifactRequestDetector {

    private static final Pattern OUTPUT_INTENT = Pattern.compile(
            "(?i)\\b(create|generate|make|save|export|download|write|produce|build)\\b");
    private static final Pattern NEGATED_INTENT = Pattern.compile(
            "(?i)\\b(do not|don't|dont|without)\\s+(create|generate|make|save|export|download|write|produce|build)\\b");
    private static final Pattern NAMED_FILE = Pattern.compile(
            "(?i)\\b(?:as|named?|filename)\\s+[\\\"']?([A-Za-z0-9][A-Za-z0-9._ -]{0,119}\\.[A-Za-z0-9]{1,12})[\\\"']?");

    private final List<Format> formats;

    public AiArtifactRequestDetector(List<AiArtifactRenderer> renderers) {
        List<Format> available = new ArrayList<>();
        for (AiArtifactRenderer renderer : renderers != null ? renderers : List.<AiArtifactRenderer>of()) {
            Set<String> names = new LinkedHashSet<>(renderer.aliases());
            names.add(renderer.format());
            names.add(renderer.defaultExtension());
            available.add(new Format(renderer.format(), renderer.defaultExtension(), Set.copyOf(names)));
        }
        this.formats = List.copyOf(available);
    }

    public List<Request> detect(String prompt) {
        if (!StringUtils.hasText(prompt) || !OUTPUT_INTENT.matcher(prompt).find()
                || NEGATED_INTENT.matcher(prompt).find()) return List.of();
        String normalized = prompt.toLowerCase(Locale.ROOT);
        String namedFile = namedFile(prompt);
        List<Request> requests = new ArrayList<>();
        for (Format format : formats) {
            boolean mentioned = format.names().stream().map(String::toLowerCase)
                    .anyMatch(name -> containsToken(normalized, name));
            if (!mentioned && namedFile != null) {
                mentioned = namedFile.toLowerCase(Locale.ROOT).endsWith("." + format.extension());
            }
            if (mentioned) {
                String filename = namedFile != null && namedFile.toLowerCase(Locale.ROOT)
                        .endsWith("." + format.extension()) ? namedFile : null;
                requests.add(new Request(format.id(), filename));
            }
        }
        return List.copyOf(requests);
    }

    private String namedFile(String prompt) {
        Matcher matcher = NAMED_FILE.matcher(prompt);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private boolean containsToken(String text, String token) {
        if (!StringUtils.hasText(token)) return false;
        return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(token.toLowerCase(Locale.ROOT))
                + "(?![A-Za-z0-9])").matcher(text).find();
    }

    private record Format(String id, String extension, Set<String> names) {}

    public record Request(String format, String filename) {}
}

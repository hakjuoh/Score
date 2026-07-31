package org.oagi.score.gateway.http.api.ai_management.observability;

import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.function.Function;

/** Resolves a configured model alias while keeping observability failure-tolerant. */
final class AiRequestModelResolver {

    private final Function<String, String> resolver;

    AiRequestModelResolver(Function<String, String> resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    String resolve(String alias) {
        String fallback = AiObservationLabels.value(alias);
        try {
            String resolved = resolver.apply(alias);
            return StringUtils.hasText(resolved) ? resolved.strip() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}

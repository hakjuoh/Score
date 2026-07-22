package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.util.List;
import java.util.Objects;

/** Opens request-scoped protocol resources and returns core Tools. */
public interface ToolProvider {

    String id();

    ToolSession open(ToolResolutionContext context);

    record ToolResolutionContext(ExecutionScope scope, String accessMode) {
        public ToolResolutionContext {
            Objects.requireNonNull(scope, "scope");
            accessMode = Objects.requireNonNullElse(accessMode, "none");
        }
    }

    interface ToolSession extends AutoCloseable {
        ToolSet tools();
        @Override default void close() { }
    }

    final class Registry {
        private final List<ToolProvider> providers;
        public Registry(List<ToolProvider> providers) {
            this.providers = providers != null
                    ? providers.stream().filter(Objects::nonNull).toList() : List.of();
        }
        public List<ToolProvider> installed() { return providers; }
    }
}

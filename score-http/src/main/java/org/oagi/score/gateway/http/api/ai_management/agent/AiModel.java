package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.Objects;

/** Immutable, provider-neutral model metadata used when binding an {@link AgentSession}. */
public record AiModel(ModelId id, ProviderId provider, ModelCapabilities capabilities,
                      ContextWindow contextWindow) {

    public AiModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        capabilities = capabilities != null ? capabilities : ModelCapabilities.TEXT_ONLY;
        contextWindow = contextWindow != null ? contextWindow : ContextWindow.UNKNOWN;
    }

    public record ModelId(String value) {
        public ModelId {
            value = requireIdentifier(value, "model id");
        }
    }

    public record ProviderId(String value) {
        public ProviderId {
            value = requireIdentifier(value, "provider id");
        }
    }

    public record ModelCapabilities(boolean tools, boolean streaming, boolean usage,
                                    boolean multimodal) {
        public static final ModelCapabilities TEXT_ONLY =
                new ModelCapabilities(false, true, true, false);
    }

    public record ContextWindow(Long inputTokens, Long outputTokens) {
        public static final ContextWindow UNKNOWN = new ContextWindow(null, null);

        public ContextWindow {
            if (inputTokens != null && inputTokens <= 0 || outputTokens != null && outputTokens < 0) {
                throw new IllegalArgumentException("Context-window limits must be positive.");
            }
        }
    }

    private static String requireIdentifier(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("Invalid " + label + ".");
        }
        return normalized;
    }
}

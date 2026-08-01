package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

/** Named model support policy for version-sensitive Anthropic chat options. */
record AnthropicChatOptionSupport(List<String> thinkingModes, String defaultThinking,
                                  boolean legacySampling, boolean outputConfig,
                                  boolean citations, boolean skills,
                                  boolean fixedThinkingDeprecated) {
    AnthropicChatOptionSupport {
        thinkingModes = List.copyOf(thinkingModes);
        if (thinkingModes.isEmpty() || !thinkingModes.contains(defaultThinking)) {
            throw new IllegalArgumentException("The default thinking mode must be supported.");
        }
    }

    static AnthropicChatOptionSupport haiku45() {
        return new AnthropicChatOptionSupport(List.of("enabled", "disabled"), "disabled",
                true, false, false, false, false);
    }

    static AnthropicChatOptionSupport claude45() {
        return new AnthropicChatOptionSupport(List.of("enabled", "disabled"), "disabled",
                true, false, true, true, false);
    }

    static AnthropicChatOptionSupport claude46() {
        return new AnthropicChatOptionSupport(List.of("enabled", "adaptive", "disabled"),
                "adaptive", true, true, true, true, true);
    }

    static AnthropicChatOptionSupport adaptive() {
        return adaptive(List.of("adaptive", "disabled"));
    }

    static AnthropicChatOptionSupport alwaysAdaptive() {
        return adaptive(List.of("adaptive"));
    }

    private static AnthropicChatOptionSupport adaptive(List<String> thinkingModes) {
        return new AnthropicChatOptionSupport(thinkingModes, "adaptive",
                false, true, true, true, false);
    }
}

package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import java.util.List;

/** Named model support policy for version-sensitive Anthropic chat options. */
record AnthropicChatOptionSupport(List<String> thinkingModes, String defaultThinking,
                                  boolean legacySampling, List<String> outputEfforts,
                                  boolean citations, boolean skills,
                                  boolean fixedThinkingDeprecated) {
    AnthropicChatOptionSupport {
        thinkingModes = List.copyOf(thinkingModes);
        outputEfforts = List.copyOf(outputEfforts);
        if (thinkingModes.isEmpty() || !thinkingModes.contains(defaultThinking)) {
            throw new IllegalArgumentException("The default thinking mode must be supported.");
        }
    }

    static AnthropicChatOptionSupport adaptive() {
        return adaptive(List.of("adaptive", "disabled"));
    }

    static AnthropicChatOptionSupport alwaysAdaptive() {
        return adaptive(List.of("adaptive"));
    }

    private static AnthropicChatOptionSupport adaptive(List<String> thinkingModes) {
        return new AnthropicChatOptionSupport(thinkingModes, "adaptive",
                false, AnthropicOutputEfforts.THROUGH_XHIGH_AND_MAX, true, true, false);
    }
}

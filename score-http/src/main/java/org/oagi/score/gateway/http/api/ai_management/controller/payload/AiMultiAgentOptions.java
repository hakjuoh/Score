package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Server-authoritative, bounded multi-agent execution settings for one assistant turn. */
public record AiMultiAgentOptions(Boolean enabled, Integer maxAgents, String strategy) {

    public static final int MIN_AGENTS = 2;
    public static final int MAX_AGENTS = 4;
    public static final int DEFAULT_AGENTS = 3;
    public static final String DEFAULT_STRATEGY = "balanced";
    private static final Set<String> STRATEGIES =
            Set.of(DEFAULT_STRATEGY, "creative", "verification");

    public AiMultiAgentOptions {
        enabled = Boolean.TRUE.equals(enabled);
        maxAgents = maxAgents != null ? maxAgents : DEFAULT_AGENTS;
        strategy = StringUtils.hasText(strategy)
                ? strategy.strip().toLowerCase(Locale.ROOT) : DEFAULT_STRATEGY;
        if (maxAgents < MIN_AGENTS || maxAgents > MAX_AGENTS) {
            throw new IllegalArgumentException("multiAgent.maxAgents must be between 2 and 4.");
        }
        if (!STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException(
                    "multiAgent.strategy must be balanced, creative, or verification.");
        }
    }

    public static AiMultiAgentOptions single() {
        return new AiMultiAgentOptions(false, DEFAULT_AGENTS, DEFAULT_STRATEGY);
    }

    public boolean active() {
        return Boolean.TRUE.equals(enabled);
    }

    public Map<String, Object> asMap() {
        return Map.of("enabled", active(), "maxAgents", maxAgents, "strategy", strategy);
    }
}

package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.anthropic;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ReasoningEffort;

import java.util.List;

/** Provider-supported Anthropic output-effort sets, in increasing effort order. */
final class AnthropicOutputEfforts {
    static final List<String> THROUGH_HIGH = List.of("low", "medium", "high");
    static final List<String> THROUGH_MAX = List.of("low", "medium", "high", "max");
    static final List<String> THROUGH_XHIGH_AND_MAX =
            List.of("low", "medium", "high", "xhigh", "max");

    private AnthropicOutputEfforts() {}

    static List<ReasoningEffort> profiles(List<String> names) {
        return names.stream().map(name -> new ReasoningEffort(
                name, displayName(name), description(name), name.equals("high"),
                names.indexOf(name))).toList();
    }

    private static String displayName(String name) {
        return switch (name) {
            case "low" -> "Low";
            case "medium" -> "Medium";
            case "high" -> "High";
            case "xhigh" -> "Extra High";
            case "max" -> "Maximum";
            default -> throw new IllegalArgumentException("Unknown Anthropic effort: " + name);
        };
    }

    private static String description(String name) {
        return switch (name) {
            case "low" -> "Fast responses with lighter reasoning.";
            case "medium" -> "Balanced speed and reasoning depth for everyday tasks.";
            case "high" -> "Greater reasoning depth for complex work.";
            case "xhigh" -> "Deep reasoning for the most complex agentic work.";
            case "max" -> "Maximum capability without token-spending constraints.";
            default -> throw new IllegalArgumentException("Unknown Anthropic effort: " + name);
        };
    }
}

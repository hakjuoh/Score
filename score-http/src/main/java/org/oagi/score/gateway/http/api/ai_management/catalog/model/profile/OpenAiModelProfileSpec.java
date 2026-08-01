package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

/** Source-backed capabilities that vary within the GPT-5 model family. */
record OpenAiModelProfileSpec(long contextWindow, long maxOutputTokens,
                              List<String> reasoningEffortNames, String defaultReasoningEffort,
                              boolean structuredOutputs, boolean streaming,
                              boolean functionCalling, boolean chatCompletions,
                              boolean providerCompaction) {
    OpenAiModelProfileSpec {
        if (contextWindow <= 0 || maxOutputTokens <= 0 || maxOutputTokens >= contextWindow) {
            throw new IllegalArgumentException("OpenAI token limits must be positive and coherent.");
        }
        reasoningEffortNames = List.copyOf(reasoningEffortNames);
        if (reasoningEffortNames.isEmpty()
                || !reasoningEffortNames.contains(defaultReasoningEffort)) {
            throw new IllegalArgumentException("The default reasoning effort must be supported.");
        }
    }

    List<ReasoningEffort> reasoningEfforts() {
        return reasoningEffortNames.stream().map(name -> new ReasoningEffort(
                name, displayName(name), description(name), name.equals(defaultReasoningEffort),
                reasoningEffortNames.indexOf(name))).toList();
    }

    private static String displayName(String name) {
        return switch (name) {
            case "low" -> "Low";
            case "medium" -> "Medium";
            case "high" -> "High";
            case "xhigh" -> "Extra High";
            case "max" -> "Maximum";
            default -> throw new IllegalArgumentException("Unknown reasoning effort: " + name);
        };
    }

    private static String description(String name) {
        return switch (name) {
            case "low" -> "Fast responses with lighter reasoning.";
            case "medium" -> "Balanced speed and reasoning depth for everyday tasks.";
            case "high" -> "Greater reasoning depth for complex problems.";
            case "xhigh" -> "Deep reasoning for the most complex agentic work.";
            case "max" -> "Maximum reasoning for the hardest quality-first workloads.";
            default -> throw new IllegalArgumentException("Unknown reasoning effort: " + name);
        };
    }
}

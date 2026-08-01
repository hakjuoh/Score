package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.List;

/**
 * GPT-5 specifications from the OpenAI model pages current for Spring AI 2.0.0.
 * Pro models are retained because they were explicitly requested, even though OpenAI marks
 * their aliases as Responses-API-only and Spring AI's OpenAiChatModel uses Chat Completions.
 */
final class OpenAiModelProfileSpecs {
    private OpenAiModelProfileSpecs() {}

    static final OpenAiModelProfileSpec GPT_56 = spec(
            1_050_000L, 128_000L, List.of("low", "medium", "high", "xhigh", "max"),
            "medium", true, true, true, true, true);
    static final OpenAiModelProfileSpec GPT_55 = spec(
            1_050_000L, 128_000L, List.of("low", "medium", "high", "xhigh"),
            "medium", true, true, true, true, false);
    static final OpenAiModelProfileSpec GPT_55_PRO = spec(
            1_050_000L, 128_000L, List.of("medium", "high", "xhigh"),
            "high", true, false, true, false, false);
    static final OpenAiModelProfileSpec GPT_54 = spec(
            1_050_000L, 128_000L, List.of("low", "medium", "high", "xhigh"),
            "medium", true, true, true, true, false);
    static final OpenAiModelProfileSpec GPT_54_PRO = spec(
            1_050_000L, 128_000L, List.of("medium", "high", "xhigh"),
            "medium", false, true, true, false, false);
    static final OpenAiModelProfileSpec GPT_54_MINI = spec(
            400_000L, 128_000L, List.of("low", "medium", "high", "xhigh"),
            "medium", true, true, true, true, false);
    static final OpenAiModelProfileSpec GPT_54_NANO = GPT_54_MINI;

    private static OpenAiModelProfileSpec spec(
            long contextWindow, long maxOutputTokens, List<String> reasoningEfforts,
            String defaultReasoningEffort, boolean structuredOutputs, boolean streaming,
            boolean functionCalling, boolean chatCompletions, boolean providerCompaction) {
        return new OpenAiModelProfileSpec(contextWindow, maxOutputTokens, reasoningEfforts,
                defaultReasoningEffort, structuredOutputs, streaming, functionCalling,
                chatCompletions, providerCompaction);
    }
}

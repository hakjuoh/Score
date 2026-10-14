package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai;

import java.util.List;

/**
 * GPT-6 specifications from the OpenAI model pages (developers.openai.com/api/docs/models).
 * Reasoning-disabled models expose the application-level {@code disabled} choice, which the
 * request factory translates to OpenAI's provider-level {@code none} value.
 */
final class OpenAiModelProfileSpecs {
    private OpenAiModelProfileSpecs() {}

    // gpt-6-astra does not accept the none effort; the model page does not state its default,
    // so medium (the default of the other GPT-6 models) is assumed.
    static final OpenAiModelProfileSpec GPT_6_ASTRA = spec(
            1_050_000L, 128_000L, List.of("low", "medium", "high", "xhigh", "max"),
            "medium", true, true, true, true, true);
    static final OpenAiModelProfileSpec GPT_61_SOL = spec(
            1_050_000L, 128_000L, List.of("low", "medium", "high", "xhigh", "max"),
            "medium", true, true, true, true, true);
    static final OpenAiModelProfileSpec GPT_6_LUNA = spec(
            1_050_000L, 128_000L, List.of("disabled", "low", "medium", "high", "xhigh", "max"),
            "medium", true, true, true, true, true);

    private static OpenAiModelProfileSpec spec(
            long contextWindow, long maxOutputTokens, List<String> reasoningEfforts,
            String defaultReasoningEffort, boolean structuredOutputs, boolean streaming,
            boolean functionCalling, boolean chatCompletions, boolean providerCompaction) {
        return new OpenAiModelProfileSpec(contextWindow, maxOutputTokens, reasoningEfforts,
                defaultReasoningEffort, structuredOutputs, streaming, functionCalling,
                chatCompletions, providerCompaction);
    }
}

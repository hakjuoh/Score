package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ProviderPromptTokenNormalizerTest {

    @Test
    void addsAnthropicCacheTokensToProviderInput() {
        ProviderPromptTokenNormalizer normalizer =
                ProviderPromptTokenNormalizer.forProvider(" anthropic ");

        ProviderPromptTokenNormalizer.Snapshot result = normalizer.normalize(
                new DefaultUsage(2, 4, 6, null, 100L, 5L), false);

        assertThat(result).isEqualTo(
                new ProviderPromptTokenNormalizer.Snapshot(2L, 107L, true));
        assertThat(normalizer.wireValue()).isEqualTo("cache_excluded");
    }

    @Test
    void keepsOpenAiCacheTokensAsSubsetOfPromptTotal() {
        for (String provider : new String[]{"openai", "azure-openai"}) {
            ProviderPromptTokenNormalizer normalizer =
                    ProviderPromptTokenNormalizer.forProvider(provider);

            ProviderPromptTokenNormalizer.Snapshot result = normalizer.normalize(
                    new DefaultUsage(107, 4, 111, null, 100L, 5L), false);

            assertThat(result).as(provider).isEqualTo(
                    new ProviderPromptTokenNormalizer.Snapshot(107L, 107L, true));
            assertThat(normalizer.wireValue()).as(provider).isEqualTo("cache_included");
        }
    }

    @Test
    void marksUnknownProviderAccountingIncompleteWithoutDroppingObservedTokens() {
        ProviderPromptTokenNormalizer normalizer =
                ProviderPromptTokenNormalizer.forProvider("custom-provider");

        ProviderPromptTokenNormalizer.Snapshot result = normalizer.normalize(
                new DefaultUsage(107, 4, 111, null, 100L, 5L), false);

        assertThat(result).isEqualTo(
                new ProviderPromptTokenNormalizer.Snapshot(107L, 212L, false));
        assertThat(normalizer.wireValue()).isEqualTo("unknown");
    }

    @Test
    void marksAnthropicStreamingUsageIncompleteWhenCacheFieldsAreMissing() {
        ProviderPromptTokenNormalizer normalizer =
                ProviderPromptTokenNormalizer.forProvider("anthropic");

        ProviderPromptTokenNormalizer.Snapshot result = normalizer.normalize(
                new DefaultUsage(2, 4, 6, null, null, null), true);

        assertThat(result).isEqualTo(
                new ProviderPromptTokenNormalizer.Snapshot(2L, 2L, false));
    }

    @Test
    void treatsBlankProviderAsUnknownAndRejectsMissingUsage() {
        ProviderPromptTokenNormalizer normalizer =
                ProviderPromptTokenNormalizer.forProvider("  ");

        assertThat(normalizer.wireValue()).isEqualTo("unknown");
        assertThatNullPointerException().isThrownBy(() -> normalizer.normalize(null, false))
                .withMessage("usage");
    }
}

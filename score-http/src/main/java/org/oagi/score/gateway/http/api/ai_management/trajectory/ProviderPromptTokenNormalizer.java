package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Objects;

/** Normalizes provider-native prompt usage to ATIF's cache-inclusive token count. */
final class ProviderPromptTokenNormalizer {

    private final Accounting accounting;

    private ProviderPromptTokenNormalizer(Accounting accounting) {
        this.accounting = accounting;
    }

    static ProviderPromptTokenNormalizer forProvider(String providerType) {
        if (!StringUtils.hasText(providerType)) {
            return new ProviderPromptTokenNormalizer(Accounting.UNKNOWN);
        }
        Accounting accounting = switch (providerType.strip().toLowerCase(Locale.ROOT)) {
            case "anthropic" -> Accounting.CACHE_EXCLUDED;
            case "openai", "azure-openai" -> Accounting.CACHE_INCLUDED;
            default -> Accounting.UNKNOWN;
        };
        return new ProviderPromptTokenNormalizer(accounting);
    }

    String wireValue() {
        return accounting.wireValue;
    }

    Snapshot normalize(Usage usage, boolean streaming) {
        Objects.requireNonNull(usage, "usage");
        Number reported = usage.getPromptTokens();
        long providerTokens = reported != null ? reported.longValue() : 0L;
        long cacheReadTokens = Objects.requireNonNullElse(
                usage.getCacheReadInputTokens(), 0L);
        long cacheWriteTokens = Objects.requireNonNullElse(
                usage.getCacheWriteInputTokens(), 0L);
        long cacheTokens = cacheReadTokens + cacheWriteTokens;
        long inclusiveTokens = accounting == Accounting.CACHE_INCLUDED
                ? providerTokens : providerTokens + cacheTokens;
        boolean missingStreamingCacheUsage = accounting == Accounting.CACHE_EXCLUDED && streaming
                && usage.getCacheReadInputTokens() == null
                && usage.getCacheWriteInputTokens() == null;
        boolean complete = reported != null
                && accounting != Accounting.UNKNOWN
                && !missingStreamingCacheUsage;
        return new Snapshot(providerTokens, inclusiveTokens, complete);
    }

    record Snapshot(long providerReportedTokens, long inclusiveTokens, boolean complete) {
    }

    private enum Accounting {
        CACHE_EXCLUDED("cache_excluded"),
        CACHE_INCLUDED("cache_included"),
        UNKNOWN("unknown");

        private final String wireValue;

        Accounting(String wireValue) {
            this.wireValue = wireValue;
        }
    }
}

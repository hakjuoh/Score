package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Spring AI Anthropic reference chat options, filtered by each Claude support policy. */
final class AnthropicChatOptionProfiles {
    private AnthropicChatOptionProfiles() {}

    static List<AiModelOption> options(String model, int maxTokens,
                                       AnthropicChatOptionSupport support) {
        List<AiModelOption> options = new ArrayList<>();
        options.add(string("model", model, "Name of the Claude model sent to Anthropic."));
        options.add(integer("maxTokens", maxTokens,
                "Maximum number of tokens to generate in the response."));
        options.add(decimal("temperature", 1.0,
                "Sampling randomness from 0.0 to 1.0. Anthropic requires 1.0 when extended thinking is enabled."));
        if (support.legacySampling()) {
            options.add(decimal("topP", null,
                    "Nucleus sampling probability. Supported through Claude Opus 4.6; later models deprecate or reject it."));
            options.add(integer("topK", null,
                    "Number of highest-probability tokens considered. Supported through Claude Opus 4.6 and rejected by later models."));
        }
        options.add(json("stopSequences", null,
                "JSON array of custom sequences that stop generation."));
        options.add(string("apiKey", "",
                "Anthropic API key. Normally inherited from the provider configuration or ANTHROPIC_API_KEY."));
        options.add(string("baseUrl", "",
                "Anthropic API base URL. An empty value uses the provider endpoint."));
        options.add(string("timeout", "60s", "Request timeout duration."));
        options.add(integer("maxRetries", 2, "Maximum number of SDK retry attempts."));
        options.add(string("proxy", "", "HTTP proxy configuration."));
        options.add(json("customHeaders", null,
                "JSON object of client-level custom HTTP headers."));
        options.add(json("httpHeaders", null,
                "JSON object of per-request HTTP headers, including beta or routing headers."));
        options.add(json("metadata", null,
                "JSON request metadata, including an optional user identifier for abuse detection."));
        options.add(json("cacheOptions", null,
                "JSON representation of the complete AnthropicCacheOptions object."));
        options.add(enumeration("cacheStrategy", "CONVERSATION_HISTORY",
                "Profile prompt-caching strategy; Spring AI itself defaults to NONE.",
                "NONE", "TOOLS_ONLY", "SYSTEM_ONLY", "SYSTEM_AND_TOOLS",
                "CONVERSATION_HISTORY"));
        options.add(json("messageTypeTtl", Map.of(),
                "JSON map from message type to cache TTL (FIVE_MINUTES or ONE_HOUR); omitted types default to FIVE_MINUTES."));
        options.add(json("messageTypeMinContentLengths", Map.of(),
                "JSON map from message type to its minimum cacheable content length; omitted types default to 1."));
        options.add(string("contentLengthFunction", "String::length",
                "Function used to compute cacheable content length."));
        options.add(bool("multiBlockSystemCaching", true,
                "Cache separate system-message blocks so a stable prefix can be reused."));
        options.add(enumeration("thinking", support.defaultThinking(),
                thinkingDescription(support),
                support.thinkingModes().toArray(String[]::new)));
        if (support.thinkingModes().contains("enabled")) {
            options.add(integer("thinkingBudgetTokens", null,
                    support.fixedThinkingDeprecated()
                            ? "Token budget for the deprecated enabled fixed-thinking mode; use adaptive thinking instead. It must be at least 1024 and below maxTokens."
                            : "Token budget for enabled fixed thinking; it must be at least 1024 and below maxTokens."));
        }
        options.add(enumeration("thinkingDisplay", null,
                "Controls whether returned thinking is summarized or omitted.",
                "SUMMARIZED", "OMITTED"));
        if (support.outputConfig()) {
            options.add(json("outputConfig", null,
                    "JSON output configuration combining a structured-output schema and effort."));
            options.add(json("outputSchema", null,
                    "JSON Schema used for Anthropic structured output."));
            options.add(enumeration("outputEffort", "HIGH",
                    "Effort level used by outputConfig.", "LOW", "MEDIUM", "HIGH", "MAX"));
        }
        options.add(enumeration("inferenceGeo", null,
                "Geographic region where inference is processed.", "us", "eu"));
        options.add(enumeration("serviceTier", null,
                "Capacity routing: use priority capacity when available or standard capacity only.",
                "AUTO", "STANDARD_ONLY"));
        if (support.citations()) {
            options.add(json("citationDocuments", null,
                    "JSON array of plain-text, PDF, or custom-content citation documents."));
            options.add(bool("citationsEnabled", false,
                    "Enable source citations for every citation document in the request."));
            options.add(enumeration("citationDocumentType", "PLAIN_TEXT",
                    "Citation document representation.", "PLAIN_TEXT", "PDF", "CUSTOM_CONTENT"));
            options.add(string("plainText", "", "Plain-text source for a citation document."));
            options.add(string("pdf", "", "Base64-encoded PDF bytes for a citation document."));
            options.add(json("customContent", null,
                    "JSON array of source text blocks for a custom-content citation document."));
            options.add(string("title", "", "Human-readable citation document title."));
            options.add(string("context", "",
                    "Additional document context that guides Claude but is not cited."));
        }
        if (support.skills()) {
            options.add(json("skillContainer", null,
                    "JSON array of Claude skill IDs or names, optionally with versions; at most eight."));
            options.add(string("skillIdOrName", "",
                    "Claude skill identifier or built-in skill name."));
            options.add(string("skillVersion", "",
                    "Optional pinned version for the selected Claude skill."));
        }
        return List.copyOf(options);
    }

    private static String thinkingDescription(AnthropicChatOptionSupport support) {
        return support.fixedThinkingDeprecated()
                ? "Extended-thinking mode for the request. On Claude 4.6, enabled fixed thinking is deprecated; use adaptive instead."
                : "Extended-thinking mode for the request.";
    }

    private static AiModelOption bool(String key, Boolean value, String description) {
        return new AiModelOption(key, "boolean", value, description, List.of());
    }

    private static AiModelOption integer(String key, Number value, String description) {
        return new AiModelOption(key, "integer", value, description, List.of());
    }

    private static AiModelOption decimal(String key, Number value, String description) {
        return new AiModelOption(key, "decimal", value, description, List.of());
    }

    private static AiModelOption string(String key, String value, String description) {
        return new AiModelOption(key, "string", value, description, List.of());
    }

    private static AiModelOption json(String key, Object value, String description) {
        return new AiModelOption(key, "json", value, description, List.of());
    }

    private static AiModelOption enumeration(String key, String value, String description,
                                             String... allowedValues) {
        return new AiModelOption(key, "enum", value, description, List.of(allowedValues));
    }
}

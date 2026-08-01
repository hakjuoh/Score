package org.oagi.score.gateway.http.configuration.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Metadata;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolChoiceTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiUiRouteManifest;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicCitationDocument;
import org.springframework.ai.anthropic.AnthropicServiceTier;
import org.springframework.ai.anthropic.AnthropicSkillContainer;
import org.springframework.ai.anthropic.AnthropicWebSearchTool;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates provider-specific Spring AI options for one assistant model call. */
@Component
public final class ScoreAiChatOptionsFactory {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ScoreAiModelRegistry models;
    private final AnthropicChatProperties anthropicProperties;
    private final OpenAiChatProperties openAiProperties;

    public ScoreAiChatOptionsFactory(ScoreAiModelRegistry models,
                                     AnthropicChatProperties anthropicProperties,
                                     OpenAiChatProperties openAiProperties) {
        this.models = models;
        this.anthropicProperties = anthropicProperties;
        this.openAiProperties = openAiProperties;
    }

    public ChatOptions create(String modelName, String reasoningEffort,
                              AiUiRouteManifest routeManifest) {
        return create(modelName, reasoningEffort, routeManifest, null);
    }

    public ChatOptions create(String modelName, String reasoningEffort,
                              AiUiRouteManifest routeManifest, String requestId) {
        ScoreAiModelRegistry.ModelConfiguration model = requestId != null
                ? models.modelConfiguration(modelName, requestId)
                : models.modelConfiguration(modelName);
        return switch (model.providerType()) {
            case "anthropic" -> anthropicOptions(model, reasoningEffort);
            case "openai", "azure-openai" -> openAiOptions(model, reasoningEffort, routeManifest);
            default -> throw new IllegalArgumentException(
                    "Spring AI does not support provider: " + model.providerType());
        };
    }

    private AnthropicChatOptions anthropicOptions(
            ScoreAiModelRegistry.ModelConfiguration model, String reasoningEffort) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model(model.model());
        Map<String, Object> options = model.modelOptions();
        boolean thinkingModel = model.adaptiveThinking() || model.thinkingBudgetTokens() != null;
        anthropicProperties.apply(builder, thinkingModel);
        if (model.maxTokens() != null) builder.maxTokens(model.maxTokens());
        if (model.temperature() != null && model.supportsTemperature()) {
            builder.temperature(model.temperature());
        }
        number(options, "topP").ifPresent(value -> builder.topP(value.doubleValue()));
        number(options, "topK").ifPresent(value -> builder.topK(value.intValue()));
        stringList(options, "stopSequences").ifPresent(builder::stopSequences);
        map(options, "metadata").map(ScoreAiChatOptionsFactory::anthropicMetadata)
                .ifPresent(builder::metadata);
        anthropicToolChoice(options).ifPresent(builder::toolChoice);
        bool(options, "disableParallelToolUse").ifPresent(builder::disableParallelToolUse);
        string(options, "inferenceGeo").ifPresent(builder::inferenceGeo);
        string(options, "serviceTier").ifPresent(value ->
                builder.serviceTier(AnthropicServiceTier.valueOf(value)));
        applyAnthropicOutput(builder, options);
        String thinking = string(options, "thinking").orElse(model.resolvedDefaultThinking());
        String normalizedReasoningEffort = StringUtils.hasText(reasoningEffort)
                ? reasoningEffort.strip().toLowerCase() : "";
        boolean thinkingDisabled = "disabled".equals(normalizedReasoningEffort)
                || "disabled".equals(thinking);
        String thinkingDisplay = string(options, "thinkingDisplay").orElse(null);
        if ("disabled".equals(normalizedReasoningEffort)) {
            builder.thinkingDisabled();
        } else if ("adaptive".equals(thinking)) {
            if (thinkingDisplay != null) builder.thinkingAdaptive(
                    ThinkingConfigAdaptive.Display.of(thinkingDisplay.toLowerCase()));
            else builder.thinkingAdaptive();
        } else if ("enabled".equals(thinking) && model.thinkingBudgetTokens() != null) {
            if (thinkingDisplay != null) builder.thinkingEnabled(model.thinkingBudgetTokens(),
                    ThinkingConfigEnabled.Display.of(thinkingDisplay.toLowerCase()));
            else builder.thinkingEnabled(model.thinkingBudgetTokens());
        } else if ("disabled".equals(thinking)) {
            builder.thinkingDisabled();
        }
        String compositeOutputEffort = map(options, "outputConfig")
                .flatMap(value -> string(value, "effort")).orElse(null);
        String effectiveOutputEffort = StringUtils.hasText(normalizedReasoningEffort)
                && !"default".equals(normalizedReasoningEffort)
                ? normalizedReasoningEffort
                : StringUtils.hasText(compositeOutputEffort)
                ? compositeOutputEffort : model.outputEffort();
        if (!thinkingDisabled && model.supportsOutputEffort()
                && StringUtils.hasText(effectiveOutputEffort)) {
            builder.effort(OutputConfig.Effort.of(effectiveOutputEffort.toLowerCase()));
        }
        applyAnthropicCache(builder, model.cacheStrategy(), options);
        anthropicCitations(options).ifPresent(builder::citationDocuments);
        anthropicSkills(options).ifPresent(builder::skillContainer);
        anthropicWebSearch(options).ifPresent(builder::webSearchTool);
        return builder.build();
    }

    private OpenAiChatOptions openAiOptions(
            ScoreAiModelRegistry.ModelConfiguration model, String reasoningEffort,
            AiUiRouteManifest routeManifest) {
        boolean reasoningModel = model.openAiReasoningModel();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model.model());
        Map<String, Object> options = model.modelOptions();
        if ("azure-openai".equals(model.providerType())) {
            builder.deploymentName(model.model()).azure(true);
        }
        openAiProperties.apply(builder, reasoningModel);
        if (model.maxTokens() != null) {
            if (reasoningModel) builder.maxCompletionTokens(model.maxTokens());
            else builder.maxTokens(model.maxTokens());
        }
        if (model.temperature() != null && model.supportsTemperature()) {
            builder.temperature(model.temperature());
        }
        number(options, "frequencyPenalty").ifPresent(value ->
                builder.frequencyPenalty(value.doubleValue()));
        number(options, "presencePenalty").ifPresent(value ->
                builder.presencePenalty(value.doubleValue()));
        number(options, "topP").ifPresent(value -> builder.topP(value.doubleValue()));
        stringList(options, "stop").ifPresent(builder::stop);
        integerMap(options, "logitBias").ifPresent(builder::logitBias);
        bool(options, "logprobs").ifPresent(builder::logprobs);
        number(options, "topLogprobs").ifPresent(value -> builder.topLogprobs(value.intValue()));
        number(options, "n").ifPresent(value -> builder.n(value.intValue()));
        number(options, "seed").ifPresent(value -> builder.seed(value.intValue()));
        string(options, "user").ifPresent(builder::user);
        java.util.Optional.ofNullable(options.get("toolChoice"))
                .map(value -> value instanceof String ? value : writeJson(value, "toolChoice"))
                .ifPresent(builder::toolChoice);
        bool(options, "parallelToolCalls").ifPresent(builder::parallelToolCalls);
        bool(options, "store").ifPresent(builder::store);
        stringMap(options, "metadata").ifPresent(builder::metadata);
        string(options, "verbosity").ifPresent(builder::verbosity);
        string(options, "serviceTier").ifPresent(builder::serviceTier);
        string(options, "promptCacheKey").ifPresent(builder::promptCacheKey);
        Map<String, Object> extraBody = new LinkedHashMap<>(
                map(options, "extraBody").orElse(Map.of()));
        string(options, "safetyIdentifier").ifPresent(value ->
                extraBody.put(OpenAiResponsesRequestMapper.SAFETY_IDENTIFIER_OPTION, value));
        string(options, "responseFormatName").ifPresent(value ->
                extraBody.put(OpenAiResponsesRequestMapper.RESPONSE_FORMAT_NAME_OPTION, value));
        bool(options, "responseFormatStrict").ifPresent(value ->
                extraBody.put(OpenAiResponsesRequestMapper.RESPONSE_FORMAT_STRICT_OPTION, value));
        if (!extraBody.isEmpty()) builder.extraBody(extraBody);
        string(options, "outputModalities").ifPresent(value ->
                builder.outputModalities(List.of(value)));
        json(options, "responseFormatSchema").ifPresent(builder::outputSchema);
        applyOpenAiResponseFormat(builder, options);
        applyOpenAiStreamOptions(builder, options);
        if (reasoningModel) {
            // OpenAI's reasoning models require an explicit effort when the setting is absent.
            String effectiveReasoningEffort = !StringUtils.hasText(reasoningEffort)
                    || "disabled".equalsIgnoreCase(reasoningEffort) ? "none" : reasoningEffort;
            if (StringUtils.hasText(effectiveReasoningEffort)
                    && !"default".equalsIgnoreCase(effectiveReasoningEffort)) {
                builder.reasoningEffort(effectiveReasoningEffort.strip().toLowerCase());
            }
        }
        if (routeManifest != null) builder.promptCacheKey(routeManifest.promptCacheKey());
        return builder.build();
    }

    private static void applyOpenAiResponseFormat(OpenAiChatOptions.Builder builder,
                                                   Map<String, Object> options) {
        string(options, "responseFormatType").ifPresent(type -> {
            var format = OpenAiChatModel.ResponseFormat.builder()
                    .type(OpenAiChatModel.ResponseFormat.Type.valueOf(type));
            json(options, "responseFormatSchema").ifPresent(format::jsonSchema);
            builder.responseFormat(format.build());
        });
    }

    private static void applyOpenAiStreamOptions(OpenAiChatOptions.Builder builder,
                                                  Map<String, Object> options) {
        Map<String, Object> composite = map(options, "streamOptions").orElse(Map.of());
        var obfuscation = bool(options, "includeObfuscation");
        var additional = map(options, "streamAdditionalProperties");
        if (obfuscation.isEmpty()) obfuscation = bool(composite, "includeObfuscation");
        if (additional.isEmpty()) additional = map(composite, "additionalProperties");
        if (obfuscation.isEmpty() && additional.isEmpty()) return;
        var stream = OpenAiChatOptions.StreamOptions.builder();
        obfuscation.ifPresent(stream::includeObfuscation);
        additional.ifPresent(stream::additionalProperties);
        builder.streamOptions(stream.build());
    }

    private static void applyAnthropicCache(AnthropicChatOptions.Builder builder,
                                             String configuredStrategy,
                                             Map<String, Object> options) {
        Map<String, Object> composite = map(options, "cacheOptions").orElse(Map.of());
        String strategyName = string(composite, "strategy").orElse(configuredStrategy);
        if (!StringUtils.hasText(strategyName)) return;
        var cache = AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.valueOf(
                strategyName.replace('-', '_').toUpperCase()));
        bool(options, "multiBlockSystemCaching")
                .or(() -> bool(composite, "multiBlockSystemCaching"))
                .ifPresent(cache::multiBlockSystemCaching);
        bool(options, "cacheToolResults").or(() -> bool(composite, "cacheToolResults"))
                .ifPresent(cache::cacheToolResults);
        map(options, "messageTypeTtl").or(() -> map(composite, "messageTypeTtl"))
                .ifPresent(values -> values.forEach((name, value) -> cache.messageTypeTtl(
                        MessageType.valueOf(name.toUpperCase()),
                        AnthropicCacheTtl.valueOf(String.valueOf(value).toUpperCase()))));
        map(options, "messageTypeMinContentLengths")
                .or(() -> map(composite, "messageTypeMinContentLengths"))
                .ifPresent(values -> values.forEach((name, value) -> cache
                        .messageTypeMinContentLength(MessageType.valueOf(name.toUpperCase()),
                                ((Number) value).intValue())));
        builder.cacheOptions(cache.build());
    }

    private static Metadata anthropicMetadata(Map<String, Object> values) {
        Metadata.Builder metadata = Metadata.builder();
        Object userId = values.getOrDefault("userId", values.get("user_id"));
        if (userId instanceof String text && StringUtils.hasText(text)) metadata.userId(text);
        values.forEach((key, value) -> {
            if (!"userId".equals(key) && !"user_id".equals(key)) {
                metadata.putAdditionalProperty(key, JsonValue.from(value));
            }
        });
        return metadata.build();
    }

    private static java.util.Optional<ToolChoice> anthropicToolChoice(Map<String, Object> options) {
        String value = string(options, "toolChoice").orElse(null);
        String name = string(options, "toolChoiceName").orElse(null);
        if (!StringUtils.hasText(value)) return java.util.Optional.empty();
        return java.util.Optional.of(switch (value.toUpperCase()) {
            case "AUTO" -> ToolChoice.ofAuto(ToolChoiceAuto.builder().build());
            case "ANY" -> ToolChoice.ofAny(ToolChoiceAny.builder().build());
            case "NONE" -> ToolChoice.ofNone(ToolChoiceNone.builder().build());
            case "TOOL" -> {
                if (!StringUtils.hasText(name)) {
                    throw new IllegalArgumentException("toolChoiceName is required for TOOL.");
                }
                yield ToolChoice.ofTool(ToolChoiceTool.builder().name(name).build());
            }
            default -> throw new IllegalArgumentException("Unsupported Anthropic toolChoice: " + value);
        });
    }

    private static void applyAnthropicOutput(AnthropicChatOptions.Builder builder,
                                              Map<String, Object> options) {
        Map<String, Object> composite = map(options, "outputConfig").orElse(Map.of());
        Object schema = options.get("outputSchema");
        if (schema == null) schema = composite.getOrDefault("schema", composite.get("outputSchema"));
        if (schema == null && composite.get("format") instanceof Map<?, ?> format) {
            schema = format.get("schema");
        }
        if (schema != null) builder.outputSchema(writeJson(schema, "outputSchema"));
        string(composite, "effort").ifPresent(value ->
                builder.effort(OutputConfig.Effort.of(value.toLowerCase())));
    }

    private static java.util.Optional<List<AnthropicCitationDocument>> anthropicCitations(
            Map<String, Object> options) {
        Object configured = options.get("citationDocuments");
        boolean citationsEnabled = bool(options, "citationsEnabled").orElse(false);
        if (configured instanceof List<?> documents) {
            return java.util.Optional.of(documents.stream()
                    .map(item -> citationDocument(castMap(item), citationsEnabled)).toList());
        }
        if (string(options, "plainText").isEmpty() && string(options, "pdf").isEmpty()
                && stringList(options, "customContent").isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(List.of(citationDocument(options, citationsEnabled)));
    }

    private static AnthropicCitationDocument citationDocument(Map<String, Object> values,
                                                                boolean defaultCitationsEnabled) {
        var document = AnthropicCitationDocument.builder();
        String type = string(values, "citationDocumentType")
                .or(() -> string(values, "type")).orElse("PLAIN_TEXT").toUpperCase();
        switch (type) {
            case "PLAIN_TEXT" -> document.plainText(requiredString(values, "plainText", "text"));
            case "PDF" -> {
                String encoded = nullableString(values, "pdf");
                if (StringUtils.hasText(encoded)) {
                    document.pdf(java.util.Base64.getDecoder().decode(encoded));
                } else {
                    document.pdf(java.util.Base64.getDecoder().decode(
                            requiredString(values, "data")));
                }
            }
            case "CUSTOM_CONTENT" -> document.customContent(stringList(values, "customContent")
                    .or(() -> stringList(values, "content")).orElseThrow().toArray(String[]::new));
            default -> throw new IllegalArgumentException("Unsupported citation document type: " + type);
        }
        string(values, "title").ifPresent(document::title);
        string(values, "context").ifPresent(document::context);
        document.citationsEnabled(bool(values, "citationsEnabled")
                .orElse(defaultCitationsEnabled));
        return document.build();
    }

    private static java.util.Optional<AnthropicSkillContainer> anthropicSkills(
            Map<String, Object> options) {
        Object configured = options.get("skillContainer");
        var skills = AnthropicSkillContainer.builder();
        boolean[] present = {false};
        if (configured instanceof List<?> list) list.forEach(item -> {
            if (item instanceof String name) skills.skill(name);
            else {
                Map<String, Object> value = castMap(item);
                String name = requiredString(value, "skillIdOrName", "skill_id", "name");
                string(value, "version").ifPresentOrElse(version -> skills.skill(name, version),
                        () -> skills.skill(name));
            }
            present[0] = true;
        });
        string(options, "skillIdOrName").ifPresent(name -> {
            string(options, "skillVersion").ifPresentOrElse(version -> skills.skill(name, version),
                    () -> skills.skill(name));
            present[0] = true;
        });
        return present[0] ? java.util.Optional.of(skills.build()) : java.util.Optional.empty();
    }

    private static java.util.Optional<AnthropicWebSearchTool> anthropicWebSearch(
            Map<String, Object> options) {
        Map<String, Object> composite = map(options, "webSearchTool").orElse(Map.of());
        var search = AnthropicWebSearchTool.builder();
        boolean[] present = {false};
        number(options, "maxUses").or(() -> number(composite, "maxUses")).ifPresent(value -> {
            search.maxUses(value.longValue()); present[0] = true;
        });
        stringList(options, "allowedDomains").or(() -> stringList(composite, "allowedDomains"))
                .ifPresent(value -> { search.allowedDomains(value); present[0] = true; });
        stringList(options, "blockedDomains").or(() -> stringList(composite, "blockedDomains"))
                .ifPresent(value -> { search.blockedDomains(value); present[0] = true; });
        map(options, "userLocation").or(() -> map(composite, "userLocation"))
                .ifPresent(value -> {
                    search.userLocation(nullableString(value, "city"), nullableString(value, "country"),
                            nullableString(value, "region"), nullableString(value, "timezone"));
                    present[0] = true;
                });
        return present[0] ? java.util.Optional.of(search.build()) : java.util.Optional.empty();
    }

    private static java.util.Optional<String> string(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value instanceof String text && StringUtils.hasText(text)
                ? java.util.Optional.of(text) : java.util.Optional.empty();
    }

    private static java.util.Optional<Boolean> bool(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value instanceof Boolean flag
                ? java.util.Optional.of(flag) : java.util.Optional.empty();
    }

    private static java.util.Optional<Number> number(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value instanceof Number number
                ? java.util.Optional.of(number) : java.util.Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Optional<Map<String, Object>> map(
            Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value instanceof Map<?, ?> map
                ? java.util.Optional.of((Map<String, Object>) map) : java.util.Optional.empty();
    }

    private static java.util.Optional<List<String>> stringList(
            Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(list.stream().map(String.class::cast).toList());
    }

    private static java.util.Optional<Map<String, String>> stringMap(
            Map<String, Object> options, String key) {
        var value = map(options, key);
        if (value.isEmpty() || value.get().values().stream()
                .anyMatch(item -> !(item instanceof String))) return java.util.Optional.empty();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        value.get().forEach((name, item) -> result.put(name, (String) item));
        return java.util.Optional.of(result);
    }

    private static java.util.Optional<Map<String, Integer>> integerMap(
            Map<String, Object> options, String key) {
        var value = map(options, key);
        if (value.isEmpty() || value.get().values().stream()
                .anyMatch(item -> !(item instanceof Number))) return java.util.Optional.empty();
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        value.get().forEach((name, item) -> result.put(name, ((Number) item).intValue()));
        return java.util.Optional.of(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("Expected a JSON object model option.");
        }
        return (Map<String, Object>) map;
    }

    private static String requiredString(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = nullableString(values, key);
            if (StringUtils.hasText(value)) return value;
        }
        throw new IllegalArgumentException("Required model option is missing: " + keys[0]);
    }

    private static String nullableString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private static String writeJson(Object value, String key) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON model option: " + key, exception);
        }
    }

    private static java.util.Optional<String> json(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value != null ? java.util.Optional.of(writeJson(value, key))
                : java.util.Optional.empty();
    }
}

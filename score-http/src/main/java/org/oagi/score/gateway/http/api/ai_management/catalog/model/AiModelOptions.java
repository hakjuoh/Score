package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelOption;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfile;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates and assembles the JSON document used to reconstruct a configured model. */
public final class AiModelOptions {
    private static final Set<String> MODEL_SETTING_KEYS = Set.of(
            "model", "deploymentName", "maxTokens", "maxCompletionTokens", "temperature",
            "thinkingBudgetTokens", "reasoningEffort", "outputEffort");
    private static final Set<String> PROVIDER_SETTING_KEYS = Set.of(
            "apiKey", "baseUrl", "credential", "microsoftFoundryServiceVersion",
            "organizationId", "projectId", "microsoftFoundry", "gitHubModels", "timeout",
            "maxRetries", "proxy", "customHeaders", "httpHeaders");
    private static final Set<String> INTERNAL_CONFIGURATION_KEYS = Set.of(
            "providerCompactionEnabled", "temperature", "thinkingBudgetTokens",
            "adaptiveThinking", "outputEffort", "reasoningModelSupported",
            "outputEffortSupported", "verbositySupported", "temperatureSupported",
            "thinkingModes", "defaultThinking");
    private static final Set<String> APPLICATION_MANAGED_KEYS = Set.of(
            "toolCallbacks", "toolContext", "tools", "contentLengthFunction",
            "toolChoice", "toolChoiceName", "disableParallelToolUse", "parallelToolCalls",
            "cacheToolResults", "webSearchTool", "maxUses", "allowedDomains",
            "blockedDomains", "userLocation");
    private static final Set<String> STRING_LIST_KEYS = Set.of(
            "stop", "stopSequences", "allowedDomains", "blockedDomains", "customContent");
    private static final Set<String> OBJECT_KEYS = Set.of(
            "metadata", "cacheOptions", "messageTypeTtl", "messageTypeMinContentLengths",
            "outputConfig", "outputSchema", "userLocation", "webSearchTool", "logitBias",
            "responseFormatSchema", "streamOptions", "streamAdditionalProperties", "extraBody");

    private AiModelOptions() {}

    public static Map<String, Object> persisted(AiModelProfile profile,
                                                 AiModelCatalogUpdate input,
                                                 AiModelProfileSettingsResolver.ResolvedSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>(validated(profile, input.modelOptions()));
        AiModelProfileView profileView = AiModelProfileView.from(profile);
        put(values, "providerCompactionEnabled", input.providerCompactionEnabled());
        put(values, "temperature", settings.temperature());
        put(values, "thinkingBudgetTokens", settings.thinkingBudgetTokens());
        String configuredThinking = stringValue(values.get("thinking"));
        put(values, "adaptiveThinking", profileView.thinkingModes().contains("adaptive"));
        put(values, "outputEffort", normalized(input.outputEffort()));
        if (!values.containsKey("cacheStrategy")) {
            put(values, "cacheStrategy",
                    profileEnumValue(profile, "cacheStrategy", input.cacheStrategy()));
        }
        put(values, "reasoningModelSupported", settings.reasoningOptionsEnabled());
        put(values, "outputEffortSupported", settings.outputEffortEnabled());
        put(values, "verbositySupported", settings.verbosityEnabled());
        put(values, "temperatureSupported", settings.temperatureEnabled());
        put(values, "thinkingModes", profileView.thinkingModes());
        put(values, "defaultThinking", configuredThinking != null
                ? configuredThinking : normalized(input.defaultThinking()));
        return Map.copyOf(values);
    }

    public static Map<String, Object> validated(AiModelProfile profile,
                                                 Map<String, Object> requested) {
        Map<String, AiModelOption> allowed = new LinkedHashMap<>();
        profile.getOptions().stream().filter(AiModelOptions::visible)
                .forEach(option -> allowed.put(option.key(), option));
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (requested == null) return normalized;
        requested.forEach((key, value) -> {
            AiModelOption option = allowed.get(key);
            if (option == null) {
                throw new IllegalArgumentException("Unsupported model option: " + key);
            }
            Object candidate = normalizedValue(value);
            if (candidate == null) return;
            if (!valid(option, candidate)) {
                throw new IllegalArgumentException(
                        "The value for model option " + key + " does not match " + option.type() + ".");
            }
            normalized.put(key, candidate);
        });
        validateDependencies(normalized);
        return normalized;
    }

    public static Map<String, Object> profileDefaults(AiModelProfile profile) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        profile.getOptions().stream().filter(AiModelOptions::visible)
                .filter(option -> normalizedValue(option.value()) != null)
                .forEach(option -> defaults.put(option.key(), option.value()));
        return Map.copyOf(defaults);
    }

    /** Options owned by model catalog administrators and therefore exposed by the API. */
    public static List<AiModelOption> editableOptions(AiModelProfile profile) {
        return profile.getOptions().stream().filter(AiModelOptions::visible).toList();
    }

    public static Map<String, Object> editable(AiModelProfile profile,
                                                Map<String, Object> persisted) {
        Set<String> currentKeys = profile.getOptions().stream().filter(AiModelOptions::visible)
                .map(AiModelOption::key).collect(java.util.stream.Collectors.toSet());
        Map<String, Object> editable = new LinkedHashMap<>();
        persisted.forEach((key, value) -> {
            if (value != null && currentKeys.contains(key)
                    && !INTERNAL_CONFIGURATION_KEYS.contains(key)) {
                editable.put(key, value);
            }
        });
        return Map.copyOf(editable);
    }

    /** Revalidates stored options against the current profile before runtime use. */
    public static Map<String, Object> runtimeOptions(AiModelProfile profile,
                                                      Map<String, Object> persisted) {
        Map<String, Object> runtime = runtimeDefaults(profile);
        INTERNAL_CONFIGURATION_KEYS.forEach(key -> {
            Object value = persisted.get(key);
            if (value != null) {
                if (!validInternal(profile, key, value)) {
                    throw new IllegalStateException("Stored model option is invalid: " + key);
                }
                runtime.put(key, value);
            }
        });
        runtime.putAll(validated(profile, editable(profile, persisted)));
        normalizeLegacyThinking(profile, runtime);
        validateInternalConsistency(profile, runtime);
        return Map.copyOf(runtime);
    }

    private static Map<String, Object> runtimeDefaults(AiModelProfile profile) {
        AiModelProfileView view = AiModelProfileView.from(profile);
        Map<String, Object> defaults = new LinkedHashMap<>();
        put(defaults, "providerCompactionEnabled", view.providerCompactionEnabled());
        put(defaults, "temperature", view.temperature());
        put(defaults, "thinkingBudgetTokens", view.thinkingBudgetTokens());
        put(defaults, "adaptiveThinking", view.adaptiveThinking());
        put(defaults, "outputEffort", normalized(view.outputEffort()));
        put(defaults, "cacheStrategy",
                profileEnumValue(profile, "cacheStrategy", view.cacheStrategy()));
        put(defaults, "reasoningModelSupported", view.reasoningModelSupported());
        put(defaults, "outputEffortSupported", view.outputEffortSupported());
        put(defaults, "verbositySupported", view.verbositySupported());
        put(defaults, "temperatureSupported", view.temperatureSupported());
        put(defaults, "thinkingModes", view.thinkingModes());
        put(defaults, "defaultThinking", normalized(view.defaultThinking()));
        return defaults;
    }

    private static boolean visible(AiModelOption option) {
        return !MODEL_SETTING_KEYS.contains(option.key())
                && !PROVIDER_SETTING_KEYS.contains(option.key())
                && !APPLICATION_MANAGED_KEYS.contains(option.key());
    }

    private static boolean valid(AiModelOption option, Object value) {
        return switch (option.type()) {
            case "boolean" -> value instanceof Boolean;
            case "integer" -> integer(value) != null;
            case "decimal" -> value instanceof Number number
                    && Double.isFinite(number.doubleValue());
            case "string" -> value instanceof String text && validString(option.key(), text);
            case "json" -> jsonSafe(value) && validJsonShape(option.key(), value);
            case "enum" -> value instanceof String text
                    && option.allowedValues().contains(text);
            default -> false;
        } && validRange(option.key(), value);
    }

    private static boolean validJsonShape(String key, Object value) {
        if (STRING_LIST_KEYS.contains(key)) {
            return value instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)
                    && (!"stop".equals(key) || list.size() <= 4);
        }
        if ("messageTypeTtl".equals(key)) {
            return value instanceof Map<?, ?> map && map.entrySet().stream().allMatch(entry ->
                    messageType(entry.getKey()) && entry.getValue() instanceof String ttl
                            && Set.of("FIVE_MINUTES", "ONE_HOUR").contains(ttl.toUpperCase()));
        }
        if ("messageTypeMinContentLengths".equals(key)) {
            return value instanceof Map<?, ?> map && map.entrySet().stream().allMatch(entry ->
                    messageType(entry.getKey()) && integer(entry.getValue()) != null
                            && integer(entry.getValue()) >= 0);
        }
        if ("metadata".equals(key)) {
            return value instanceof Map<?, ?> map
                    && map.values().stream().allMatch(String.class::isInstance);
        }
        if ("logitBias".equals(key)) {
            return value instanceof Map<?, ?> map && map.values().stream().allMatch(item -> {
                Integer bias = integer(item);
                return bias != null && bias >= -100 && bias <= 100;
            });
        }
        if ("userLocation".equals(key)) {
            return value instanceof Map<?, ?> map
                    && map.keySet().stream().allMatch(Set.of(
                    "city", "country", "region", "timezone")::contains)
                    && map.values().stream().allMatch(String.class::isInstance);
        }
        if ("cacheOptions".equals(key)) {
            if (!(value instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(Set.of(
                    "strategy", "messageTypeTtl", "messageTypeMinContentLengths",
                    "multiBlockSystemCaching")::contains)) return false;
            return (!map.containsKey("strategy") || map.get("strategy") instanceof String strategy
                    && Set.of("NONE", "TOOLS_ONLY", "SYSTEM_ONLY", "SYSTEM_AND_TOOLS",
                    "CONVERSATION_HISTORY").contains(strategy.toUpperCase().replace('-', '_')))
                    && (!map.containsKey("multiBlockSystemCaching")
                    || map.get("multiBlockSystemCaching") instanceof Boolean)
                    && (!map.containsKey("messageTypeTtl")
                    || validJsonShape("messageTypeTtl", map.get("messageTypeTtl")))
                    && (!map.containsKey("messageTypeMinContentLengths")
                    || validJsonShape("messageTypeMinContentLengths",
                    map.get("messageTypeMinContentLengths")));
        }
        if ("streamOptions".equals(key)) {
            if (!(value instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(Set.of(
                    "includeObfuscation", "additionalProperties")::contains)) return false;
            return (!map.containsKey("includeObfuscation")
                    || map.get("includeObfuscation") instanceof Boolean)
                    && (!map.containsKey("additionalProperties")
                    || map.get("additionalProperties") instanceof Map<?, ?>);
        }
        if ("webSearchTool".equals(key)) {
            if (!(value instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(Set.of(
                    "maxUses", "allowedDomains", "blockedDomains", "userLocation")::contains)) return false;
            return (!map.containsKey("maxUses") || integer(map.get("maxUses")) != null
                    && integer(map.get("maxUses")) >= 1)
                    && (!map.containsKey("allowedDomains")
                    || validJsonShape("allowedDomains", map.get("allowedDomains")))
                    && (!map.containsKey("blockedDomains")
                    || validJsonShape("blockedDomains", map.get("blockedDomains")))
                    && (!map.containsKey("userLocation")
                    || validJsonShape("userLocation", map.get("userLocation")));
        }
        if ("outputConfig".equals(key)) {
            if (!(value instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(Set.of(
                    "effort", "schema", "outputSchema", "format")::contains)) return false;
            if (map.containsKey("effort") && (!(map.get("effort") instanceof String effort)
                    || !Set.of("LOW", "MEDIUM", "HIGH", "MAX").contains(effort.toUpperCase()))) {
                return false;
            }
            return (!map.containsKey("schema") || map.get("schema") instanceof Map<?, ?>)
                    && (!map.containsKey("outputSchema")
                    || map.get("outputSchema") instanceof Map<?, ?>)
                    && (!map.containsKey("format") || map.get("format") instanceof Map<?, ?>);
        }
        if ("extraBody".equals(key)) {
            return value instanceof Map<?, ?> map && map.keySet().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(AiModelOptions::normalizedJsonKey)
                    .noneMatch(Set.of("tools", "toolchoice", "paralleltoolcalls",
                            "functions", "functioncall")::contains);
        }
        if (OBJECT_KEYS.contains(key)) return value instanceof Map<?, ?>;
        if ("citationDocuments".equals(key)) {
            return value instanceof List<?> list && !list.isEmpty()
                    && list.stream().allMatch(AiModelOptions::citationDocument);
        }
        if ("skillContainer".equals(key)) {
            return value instanceof List<?> list && !list.isEmpty() && list.size() <= 8
                    && list.stream().allMatch(item -> item instanceof String text
                    && StringUtils.hasText(text) || skill(item));
        }
        if ("toolChoice".equals(key)) {
            if (value instanceof String text) {
                return Set.of("none", "auto", "required").contains(text.toLowerCase());
            }
            if (!(value instanceof Map<?, ?> map)
                    || !map.keySet().stream().allMatch(Set.of("type", "function")::contains)
                    || !"function".equals(map.get("type"))
                    || !(map.get("function") instanceof Map<?, ?> function)
                    || !function.keySet().stream().allMatch(Set.of("name")::contains)) return false;
            return hasText(function, "name");
        }
        return true;
    }

    private static boolean validString(String key, String value) {
        if ("toolChoice".equals(key)) {
            return Set.of("AUTO", "ANY", "NONE", "TOOL").contains(value.toUpperCase());
        }
        return !"pdf".equals(key) || validBase64(value);
    }

    private static boolean validInternal(AiModelProfile profile, String key, Object value) {
        AiModelProfileView.ModelCapabilityConstraints capabilities =
                AiModelProfileView.from(profile).capabilityConstraints();
        AiModelOption matching = profile.getOptions().stream()
                .filter(option -> option.key().equals(key)).findFirst().orElse(null);
        if (matching != null && "outputEffort".equals(key) && value instanceof String text) {
            return matching.allowedValues().stream().anyMatch(valueItem ->
                    valueItem.equalsIgnoreCase(text));
        }
        if (matching != null && "temperature".equals(key)) {
            if (!capabilities.temperature().supported()) return false;
            return valid(matching, value);
        }
        if ("thinkingBudgetTokens".equals(key)) {
            Integer budget = integer(value);
            if (budget == null) return false;
            AiModelProfileView view = AiModelProfileView.from(profile);
            var constraint = view.configurationConstraints().thinkingBudgetTokens();
            return (constraint.minimum() == null || budget >= constraint.minimum())
                    && (constraint.maximum() == null || budget <= constraint.maximum())
                    && (view.maxTokens() == null || budget < view.maxTokens());
        }
        return switch (key) {
            case "providerCompactionEnabled" -> supportedBoolean(
                    value, capabilities.providerCompaction().supported());
            case "adaptiveThinking" -> supportedBoolean(
                    value, capabilities.adaptiveThinking().supported());
            case "reasoningModelSupported" -> supportedBoolean(
                    value, capabilities.reasoningOptions().supported());
            case "outputEffortSupported" -> supportedBoolean(
                    value, capabilities.outputEffort().supported());
            case "verbositySupported" -> supportedBoolean(
                    value, capabilities.verbosity().supported());
            case "temperatureSupported" -> supportedBoolean(
                    value, capabilities.temperature().supported());
            case "thinkingModes" -> value instanceof List<?> modes
                    && modes.stream().allMatch(mode -> mode instanceof String text
                    && supportedThinkingMode(profile, text));
            case "defaultThinking" -> value instanceof String text
                    && supportedThinkingMode(profile, text);
            default -> false;
        };
    }

    private static boolean supportedBoolean(Object value, boolean supported) {
        return value instanceof Boolean enabled && (!enabled || supported);
    }

    private static void validateInternalConsistency(AiModelProfile profile,
                                                     Map<String, Object> runtime) {
        @SuppressWarnings("unchecked")
        List<String> modes = runtime.get("thinkingModes") instanceof List<?> values
                ? (List<String>) values : List.of();
        String defaultThinking = stringValue(runtime.get("defaultThinking"));
        if (defaultThinking != null && !modes.contains(defaultThinking)) {
            throw new IllegalStateException(
                    "Stored defaultThinking must be present in thinkingModes.");
        }
        boolean adaptive = Boolean.TRUE.equals(runtime.get("adaptiveThinking"));
        if (adaptive != modes.contains("adaptive")) {
            throw new IllegalStateException(
                    "Stored adaptiveThinking must match the adaptive thinking mode.");
        }
        if (runtime.containsKey("thinkingBudgetTokens")
                && !modes.contains("enabled")) {
            throw new IllegalStateException(
                    "Stored thinkingBudgetTokens requires the enabled thinking mode.");
        }
        AiModelProfileView.ModelCapabilityConstraints capabilities =
                AiModelProfileView.from(profile).capabilityConstraints();
        if (runtime.containsKey("outputEffort") && !capabilities.outputEffort().supported()) {
            throw new IllegalStateException(
                    "Stored outputEffort is not supported by the current profile.");
        }
    }

    /** Makes pre-JSON fixed-thinking rows compatible with the canonical profile modes. */
    private static void normalizeLegacyThinking(AiModelProfile profile,
                                                 Map<String, Object> runtime) {
        if (!runtime.containsKey("thinkingBudgetTokens")
                || !supportedThinkingMode(profile, "enabled")) return;
        Object storedModes = runtime.get("thinkingModes");
        if (!(storedModes instanceof List<?> modes) || !modes.contains("enabled")) {
            runtime.put("thinkingModes", AiModelProfileView.from(profile).thinkingModes());
        }
    }

    private static boolean supportedThinkingMode(AiModelProfile profile, String value) {
        return profile.getOptions().stream().filter(option -> "thinking".equals(option.key()))
                .findFirst().map(option -> option.allowedValues().contains(value)).orElse(false);
    }

    private static boolean messageType(Object value) {
        if (!(value instanceof String name)) return false;
        try {
            org.springframework.ai.chat.messages.MessageType.valueOf(name.toUpperCase());
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean citationDocument(Object value) {
        if (!(value instanceof Map<?, ?> map)) return false;
        if (!map.keySet().stream().allMatch(Set.of("citationDocumentType", "type",
                "plainText", "text", "pdf", "data", "customContent", "content",
                "title", "context", "citationsEnabled")::contains)) return false;
        if (map.containsKey("citationsEnabled") && !(map.get("citationsEnabled") instanceof Boolean)
                || map.containsKey("title") && !(map.get("title") instanceof String)
                || map.containsKey("context") && !(map.get("context") instanceof String)) return false;
        Object typeValue = map.containsKey("citationDocumentType")
                ? map.get("citationDocumentType") : map.get("type");
        String type = typeValue instanceof String text ? text.toUpperCase() : "PLAIN_TEXT";
        return switch (type) {
            case "PLAIN_TEXT" -> hasText(map, "plainText") || hasText(map, "text");
            case "PDF" -> validBase64(map.get("pdf")) || validBase64(map.get("data"));
            case "CUSTOM_CONTENT" -> {
                Object content = map.containsKey("customContent")
                        ? map.get("customContent") : map.get("content");
                yield content instanceof List<?> list && !list.isEmpty()
                        && list.stream().allMatch(String.class::isInstance);
            }
            default -> false;
        };
    }

    private static boolean skill(Object value) {
        if (!(value instanceof Map<?, ?> map)) return false;
        return map.keySet().stream().allMatch(Set.of(
                "skillIdOrName", "skill_id", "name", "version")::contains)
                && (!map.containsKey("version") || hasText(map, "version"))
                && (hasText(map, "skillIdOrName") || hasText(map, "skill_id")
                || hasText(map, "name"));
    }

    private static boolean validBase64(Object value) {
        if (!(value instanceof String text) || !StringUtils.hasText(text)) return false;
        try {
            return java.util.Base64.getDecoder().decode(text).length > 0;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasText(Map<?, ?> map, String key) {
        return map.get(key) instanceof String text && StringUtils.hasText(text);
    }

    private static boolean validRange(String key, Object value) {
        if (!(value instanceof Number number)) return true;
        BigDecimal decimal = decimal(number);
        if (decimal == null) return false;
        return switch (key) {
            case "temperature", "topP" -> between(decimal, "0", "1");
            case "frequencyPenalty", "presencePenalty" -> between(decimal, "-2", "2");
            case "topLogprobs" -> between(decimal, "0", "20");
            case "thinkingBudgetTokens" -> decimal.compareTo(BigDecimal.valueOf(1024)) >= 0;
            case "n", "maxUses" -> decimal.compareTo(BigDecimal.ONE) >= 0;
            case "topK", "seed" -> decimal.signum() >= 0;
            default -> true;
        };
    }

    private static void validateDependencies(Map<String, Object> options) {
        if ("JSON_SCHEMA".equals(options.get("responseFormatType"))
                && !options.containsKey("responseFormatSchema")) {
            throw new IllegalArgumentException(
                    "responseFormatSchema is required when responseFormatType is JSON_SCHEMA.");
        }
        if ("JSON_SCHEMA".equals(options.get("responseFormatType"))
                && stringValue(options.get("responseFormatName")) == null) {
            throw new IllegalArgumentException(
                    "responseFormatName is required when responseFormatType is JSON_SCHEMA.");
        }
        if (!"JSON_SCHEMA".equals(options.get("responseFormatType"))
                && Boolean.TRUE.equals(options.get("responseFormatStrict"))) {
            throw new IllegalArgumentException(
                    "responseFormatStrict requires responseFormatType JSON_SCHEMA.");
        }
        if ("TOOL".equalsIgnoreCase(stringValue(options.get("toolChoice")))
                && stringValue(options.get("toolChoiceName")) == null) {
            throw new IllegalArgumentException("toolChoiceName is required when toolChoice is TOOL.");
        }
    }

    private static boolean jsonSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return true;
        if (value instanceof Number number) return decimal(number) != null;
        if (value instanceof List<?> list) return list.stream().allMatch(AiModelOptions::jsonSafe);
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .allMatch(entry -> entry.getKey() instanceof String && jsonSafe(entry.getValue()));
        return false;
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number number)) return null;
        BigDecimal decimal = decimal(number);
        if (decimal == null || decimal.stripTrailingZeros().scale() > 0
                || decimal.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                || decimal.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return null;
        return decimal.intValueExact();
    }

    private static BigDecimal decimal(Number number) {
        try {
            double doubleValue = number.doubleValue();
            return Double.isFinite(doubleValue) ? new BigDecimal(number.toString()) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean between(BigDecimal value, String minimum, String maximum) {
        return value.compareTo(new BigDecimal(minimum)) >= 0
                && value.compareTo(new BigDecimal(maximum)) <= 0;
    }

    private static Object normalizedValue(Object value) {
        if (value instanceof String text && !StringUtils.hasText(text)) return null;
        return value;
    }

    private static String normalized(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static String profileEnumValue(AiModelProfile profile, String key, String value) {
        String normalized = normalized(value);
        if (normalized == null) return null;
        String comparable = normalized.replace('-', '_');
        return profile.getOptions().stream().filter(option -> option.key().equals(key))
                .flatMap(option -> option.allowedValues().stream())
                .filter(candidate -> candidate.replace('-', '_').equalsIgnoreCase(comparable))
                .findFirst().orElse(normalized);
    }

    private static String stringValue(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text.strip() : null;
    }

    private static String normalizedJsonKey(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value == null) values.remove(key);
        else values.put(key, value);
    }
}

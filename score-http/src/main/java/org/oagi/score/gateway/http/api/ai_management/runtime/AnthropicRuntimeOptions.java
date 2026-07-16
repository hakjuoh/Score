package org.oagi.score.gateway.http.api.ai_management.runtime;

import com.anthropic.models.messages.OutputConfig;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds and validates the Anthropic-specific portion of a request. */
@Component
final class AnthropicRuntimeOptions {

    private final ScoreAiModelRegistry models;
    private final AnthropicRuntimeProperties properties;

    AnthropicRuntimeOptions(ScoreAiModelRegistry models, AnthropicRuntimeProperties properties) {
        this.models = models;
        this.properties = properties;
    }

    List<AiRuntime.Setting> settings(String modelName) {
        ScoreAiModelRegistry.RuntimeModel model = model(modelName);
        List<AiRuntime.Setting> settings = new ArrayList<>();
        if (model.maxTokens() != null) {
            settings.add(new AiRuntime.Setting("maxTokens", "Maximum output tokens",
                    "Caps generated output without exceeding the model's server limit.",
                    "number", model.maxTokens(), List.of(), 1, model.maxTokens(), 1));
        }
        List<AiRuntime.Option> thinkingModes = thinkingModes(model);
        if (!thinkingModes.isEmpty()) {
            settings.add(new AiRuntime.Setting("thinking", "Thinking",
                    "Selects the thinking mode supported by this model.", "select",
                    model.resolvedDefaultThinking(), thinkingModes,
                    null, null, null));
        }
        if (model.supportedThinkingModes().contains("enabled")
                && model.thinkingBudgetTokens() != null && model.maxTokens() != null) {
            settings.add(new AiRuntime.Setting("thinkingBudgetTokens", "Thinking budget",
                    "Token budget used only when Thinking is Enabled.", "number",
                    model.thinkingBudgetTokens(), List.of(), 1024,
                    Math.max(1024, model.maxTokens() - 1), 1));
        }
        if (model.supportsTemperature() && (model.temperature() != null || properties.getTemperature() != null)) {
            settings.add(new AiRuntime.Setting("temperature", "Temperature",
                    "Controls response randomness for models without extended thinking.",
                    "number", model.temperature() != null ? model.temperature() : properties.getTemperature(),
                    List.of(), 0.0, 1.0, 0.1));
        }
        settings.add(new AiRuntime.Setting("parallelToolCalls", "Parallel tool calls",
                "Allows the model to request more than one connect-center-mcp tool at a time.",
                "boolean", !Boolean.TRUE.equals(properties.getDisableParallelToolUse()),
                List.of(), null, null, null));
        return List.copyOf(settings);
    }

    Map<String, Object> normalize(String modelName, Map<String, Object> requested) {
        ScoreAiModelRegistry.RuntimeModel model = model(modelName);
        Map<String, Object> source = requested != null ? requested : Map.of();
        Set<String> supported = settings(modelName).stream().map(AiRuntime.Setting::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        rejectUnknown(source, supported);
        Map<String, Object> result = new LinkedHashMap<>();
        if (source.containsKey("maxTokens")) {
            result.put("maxTokens", integer(source.get("maxTokens"), "maxTokens", 1, model.maxTokens()));
        }
        if (source.containsKey("thinking")) {
            String value = text(source.get("thinking"), "thinking");
            if (thinkingModes(model).stream().noneMatch(option -> option.value().equals(value))) {
                throw invalid("thinking", "is not supported by model '" + model.name() + "'");
            }
            result.put("thinking", value);
        }
        if (source.containsKey("thinkingBudgetTokens")) {
            int maximum = model.maxTokens() != null ? model.maxTokens() - 1 : Integer.MAX_VALUE;
            result.put("thinkingBudgetTokens", integer(source.get("thinkingBudgetTokens"),
                    "thinkingBudgetTokens", 1024, maximum));
        }
        if (source.containsKey("temperature")) {
            result.put("temperature", decimal(source.get("temperature"), "temperature", 0.0, 1.0));
        }
        if (source.containsKey("parallelToolCalls")) {
            result.put("parallelToolCalls", bool(source.get("parallelToolCalls"), "parallelToolCalls"));
        }
        int maxTokens = (Integer) result.getOrDefault("maxTokens",
                model.maxTokens() != null ? model.maxTokens() : AnthropicChatOptions.DEFAULT_MAX_TOKENS);
        int budget = (Integer) result.getOrDefault("thinkingBudgetTokens",
                model.thinkingBudgetTokens() != null ? model.thinkingBudgetTokens() : 1024);
        if ("enabled".equals(result.get("thinking")) && budget >= maxTokens) {
            throw invalid("thinkingBudgetTokens", "must be smaller than maxTokens");
        }
        return Map.copyOf(result);
    }

    AnthropicChatOptions options(String modelName, String reasoningEffort, Map<String, Object> requested) {
        ScoreAiModelRegistry.RuntimeModel model = model(modelName);
        Map<String, Object> values = normalize(modelName, requested);
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model(model.model());
        boolean thinkingModel = model.adaptiveThinking() || model.thinkingBudgetTokens() != null;
        properties.apply(builder, thinkingModel);
        int maxTokens = integerValue(values, "maxTokens",
                model.maxTokens() != null ? model.maxTokens() : properties.getMaxTokens());
        if (maxTokens > 0) builder.maxTokens(maxTokens);
        if (model.temperature() != null && model.supportsTemperature()) builder.temperature(model.temperature());
        if (values.get("temperature") instanceof Number value) builder.temperature(value.doubleValue());

        String thinking = values.get("thinking") instanceof String value
                ? value : model.resolvedDefaultThinking();
        if ("adaptive".equals(thinking)) {
            builder.thinkingAdaptive();
        } else if ("enabled".equals(thinking)) {
            int budget = integerValue(values, "thinkingBudgetTokens", model.thinkingBudgetTokens());
            if (budget < 1024 || budget >= maxTokens) {
                throw invalid("thinkingBudgetTokens", "must be at least 1024 and smaller than maxTokens");
            }
            builder.thinkingEnabled(budget);
        } else if ("disabled".equals(thinking)) {
            builder.thinkingDisabled();
        }
        if (!"disabled".equals(thinking) && model.supportsOutputEffort()
                && StringUtils.hasText(reasoningEffort)
                && !"default".equalsIgnoreCase(reasoningEffort)) {
            builder.effort(OutputConfig.Effort.of(reasoningEffort.toLowerCase()));
        }
        if ("conversation-history".equalsIgnoreCase(model.cacheStrategy())) {
            builder.cacheOptions(AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                    .cacheToolResults(true)
                    .build());
        }
        if (values.get("parallelToolCalls") instanceof Boolean enabled) {
            builder.disableParallelToolUse(!enabled);
        }
        return builder.build();
    }

    private ScoreAiModelRegistry.RuntimeModel model(String modelName) {
        ScoreAiModelRegistry.RuntimeModel model = models.runtimeModel(modelName);
        if (!"anthropic".equals(model.providerType())) {
            throw new IllegalArgumentException("Anthropic runtime requires an Anthropic model.");
        }
        return model;
    }

    private List<AiRuntime.Option> thinkingModes(ScoreAiModelRegistry.RuntimeModel model) {
        return model.supportedThinkingModes().stream()
                .map(mode -> new AiRuntime.Option(mode, switch (mode) {
                    case "adaptive" -> "Adaptive";
                    case "enabled" -> "Enabled";
                    case "disabled" -> "Disabled";
                    default -> mode;
                }))
                .toList();
    }

    private void rejectUnknown(Map<String, Object> values, Set<String> supported) {
        values.keySet().stream().filter(key -> !supported.contains(key)).findFirst().ifPresent(key -> {
            throw invalid(key, "is not supported by this model/runtime");
        });
    }

    private int integerValue(Map<String, Object> values, String key, Integer fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : fallback != null ? fallback : -1;
    }

    private int integer(Object value, String name, int minimum, Integer maximum) {
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw invalid(name, "must be an integer");
        }
        long result = number.longValue();
        if (result < minimum || result > (maximum != null ? maximum : Integer.MAX_VALUE)) {
            throw invalid(name, "is outside the supported range");
        }
        return (int) result;
    }

    private double decimal(Object value, String name, double minimum, double maximum) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < minimum || number.doubleValue() > maximum) {
            throw invalid(name, "is outside the supported range");
        }
        return number.doubleValue();
    }

    private boolean bool(Object value, String name) {
        if (!(value instanceof Boolean result)) throw invalid(name, "must be a boolean");
        return result;
    }

    private String text(Object value, String name) {
        if (!(value instanceof String result) || !StringUtils.hasText(result)) {
            throw invalid(name, "must be a non-empty string");
        }
        return result.strip().toLowerCase();
    }

    private IllegalArgumentException invalid(String name, String reason) {
        return new IllegalArgumentException("Runtime option '" + name + "' " + reason + ".");
    }
}

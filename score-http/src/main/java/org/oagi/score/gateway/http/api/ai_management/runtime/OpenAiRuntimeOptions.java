package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds and validates the OpenAI-specific portion of a request. */
@Component
final class OpenAiRuntimeOptions {

    private final ScoreAiModelRegistry models;
    private final OpenAiRuntimeProperties properties;

    OpenAiRuntimeOptions(ScoreAiModelRegistry models, OpenAiRuntimeProperties properties) {
        this.models = models;
        this.properties = properties;
    }

    List<AiRuntime.Setting> settings(String modelName) {
        ScoreAiModelRegistry.RuntimeModel model = model(modelName);
        List<AiRuntime.Setting> settings = new ArrayList<>();
        if (model.maxTokens() != null) {
            settings.add(new AiRuntime.Setting("maxOutputTokens", "Maximum output tokens",
                    "Caps generated output without exceeding the model's server limit.",
                    "number", model.maxTokens(), List.of(), 1, model.maxTokens(), 1));
        }
        if (model.supportsVerbosity()) {
            String defaultVerbosity = StringUtils.hasText(properties.getVerbosity())
                    ? properties.getVerbosity().strip().toLowerCase() : "medium";
            settings.add(new AiRuntime.Setting("verbosity", "Verbosity",
                    "Controls the level of detail in the visible answer.", "select", defaultVerbosity,
                    List.of(new AiRuntime.Option("low", "Low"),
                            new AiRuntime.Option("medium", "Medium"),
                            new AiRuntime.Option("high", "High")), null, null, null));
        }
        if (model.supportsTemperature()) {
            settings.add(new AiRuntime.Setting("temperature", "Temperature",
                    "Controls response randomness for non-reasoning models.", "number",
                    model.temperature() != null ? model.temperature() : properties.getTemperature(),
                    List.of(), 0.0, 2.0, 0.1));
            settings.add(new AiRuntime.Setting("frequencyPenalty", "Frequency penalty",
                    "Reduces repetition based on token frequency.", "number",
                    properties.getFrequencyPenalty(), List.of(), -2.0, 2.0, 0.1));
            settings.add(new AiRuntime.Setting("presencePenalty", "Presence penalty",
                    "Encourages introducing new topics.", "number",
                    properties.getPresencePenalty(), List.of(), -2.0, 2.0, 0.1));
        }
        settings.add(new AiRuntime.Setting("parallelToolCalls", "Parallel tool calls",
                "Allows the model to request more than one connect-center-mcp tool at a time.",
                "boolean", properties.getParallelToolCalls() == null || properties.getParallelToolCalls(),
                List.of(), null, null, null));
        return List.copyOf(settings);
    }

    Map<String, Object> normalize(String modelName, Map<String, Object> requested) {
        ScoreAiModelRegistry.RuntimeModel model = model(modelName);
        Map<String, Object> source = requested != null ? requested : Map.of();
        Set<String> supported = settings(modelName).stream().map(AiRuntime.Setting::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        source.keySet().stream().filter(key -> !supported.contains(key)).findFirst().ifPresent(key -> {
            throw invalid(key, "is not supported by model '" + model.name() + "'");
        });
        Map<String, Object> result = new LinkedHashMap<>();
        if (source.containsKey("maxOutputTokens")) {
            result.put("maxOutputTokens", integer(source.get("maxOutputTokens"),
                    "maxOutputTokens", 1, model.maxTokens()));
        }
        if (source.containsKey("verbosity")) {
            String value = text(source.get("verbosity"), "verbosity");
            if (!Set.of("low", "medium", "high").contains(value)) {
                throw invalid("verbosity", "must be low, medium, or high");
            }
            result.put("verbosity", value);
        }
        if (source.containsKey("temperature")) {
            result.put("temperature", decimal(source.get("temperature"), "temperature", 0.0, 2.0));
        }
        if (source.containsKey("frequencyPenalty")) {
            result.put("frequencyPenalty", decimal(source.get("frequencyPenalty"), "frequencyPenalty", -2.0, 2.0));
        }
        if (source.containsKey("presencePenalty")) {
            result.put("presencePenalty", decimal(source.get("presencePenalty"), "presencePenalty", -2.0, 2.0));
        }
        if (source.containsKey("parallelToolCalls")) {
            result.put("parallelToolCalls", bool(source.get("parallelToolCalls"), "parallelToolCalls"));
        }
        return Map.copyOf(result);
    }

    OpenAiChatOptions options(String modelName, String reasoningEffort, Map<String, Object> requested) {
        ScoreAiModelRegistry.RuntimeModel model = model(modelName);
        Map<String, Object> values = normalize(modelName, requested);
        boolean reasoningModel = model.openAiReasoningModel();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model.model());
        if ("azure-openai".equals(model.providerType())) {
            builder.deploymentName(model.model()).azure(true);
        }
        properties.apply(builder, reasoningModel);
        Integer maxTokens = values.get("maxOutputTokens") instanceof Number value
                ? value.intValue() : model.maxTokens();
        if (maxTokens != null) {
            if (reasoningModel) builder.maxCompletionTokens(maxTokens);
            else builder.maxTokens(maxTokens);
        }
        if (model.temperature() != null && model.supportsTemperature()) builder.temperature(model.temperature());
        if (values.get("temperature") instanceof Number value) builder.temperature(value.doubleValue());
        if (values.get("frequencyPenalty") instanceof Number value) builder.frequencyPenalty(value.doubleValue());
        if (values.get("presencePenalty") instanceof Number value) builder.presencePenalty(value.doubleValue());
        if (reasoningModel && StringUtils.hasText(reasoningEffort)
                && !"default".equalsIgnoreCase(reasoningEffort)) {
            builder.reasoningEffort(reasoningEffort.strip().toLowerCase());
        }
        if (values.get("verbosity") instanceof String value) builder.verbosity(value);
        if (values.get("parallelToolCalls") instanceof Boolean value) builder.parallelToolCalls(value);
        builder.streamUsage(true);
        return builder.build();
    }

    private ScoreAiModelRegistry.RuntimeModel model(String modelName) {
        ScoreAiModelRegistry.RuntimeModel model = models.runtimeModel(modelName);
        if (!"openai".equals(model.providerType()) && !"azure-openai".equals(model.providerType())) {
            throw new IllegalArgumentException("OpenAI runtime requires an OpenAI model.");
        }
        return model;
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

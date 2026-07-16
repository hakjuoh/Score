package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRuntimeOptionsValidationTest {

    @Test
    void normalizesSupportedProviderOptionsWithoutCoercingWrongTypes() {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        when(models.runtimeModel("claude-adaptive")).thenReturn(adaptiveAnthropicModel());
        when(models.runtimeModel("gpt-reasoning")).thenReturn(reasoningOpenAiModel());
        when(models.runtimeModel("gpt-traditional")).thenReturn(traditionalOpenAiModel());

        AnthropicRuntimeOptions anthropic = new AnthropicRuntimeOptions(
                models, new AnthropicRuntimeProperties());
        OpenAiRuntimeOptions openAi = new OpenAiRuntimeOptions(models, new OpenAiRuntimeProperties());

        assertThat(anthropic.normalize("claude-adaptive", Map.of(
                "maxTokens", 12_000, "thinking", " ADAPTIVE ", "parallelToolCalls", false)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "maxTokens", 12_000, "thinking", "adaptive", "parallelToolCalls", false));
        assertThat(openAi.normalize("gpt-reasoning", Map.of(
                "maxOutputTokens", 8_000, "verbosity", " HIGH ", "parallelToolCalls", false)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "maxOutputTokens", 8_000, "verbosity", "high", "parallelToolCalls", false));
        assertThat(openAi.normalize("gpt-traditional", Map.of(
                "temperature", 1.2, "frequencyPenalty", -0.5, "presencePenalty", 2)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "temperature", 1.2, "frequencyPenalty", -0.5, "presencePenalty", 2.0));
    }

    @Test
    void exposesOnlyOptionsSupportedByTheSelectedModel() {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        when(models.runtimeModel("claude-adaptive")).thenReturn(adaptiveAnthropicModel());
        when(models.runtimeModel("gpt-reasoning")).thenReturn(reasoningOpenAiModel());
        when(models.runtimeModel("gpt-traditional")).thenReturn(traditionalOpenAiModel());

        AnthropicRuntimeOptions anthropic = new AnthropicRuntimeOptions(
                models, new AnthropicRuntimeProperties());
        OpenAiRuntimeOptions openAi = new OpenAiRuntimeOptions(models, new OpenAiRuntimeProperties());

        assertThat(anthropic.settings("claude-adaptive")).extracting(AiRuntime.Setting::name)
                .containsExactly("maxTokens", "thinking", "parallelToolCalls");
        assertThat(openAi.settings("gpt-reasoning")).extracting(AiRuntime.Setting::name)
                .containsExactly("maxOutputTokens", "verbosity", "parallelToolCalls");
        assertThat(openAi.settings("gpt-traditional")).extracting(AiRuntime.Setting::name)
                .containsExactly("maxOutputTokens", "temperature", "frequencyPenalty",
                        "presencePenalty", "parallelToolCalls");
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidAnthropicRuntimeOptions() {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        when(models.runtimeModel("claude-adaptive")).thenReturn(adaptiveAnthropicModel());
        when(models.runtimeModel("claude-budget")).thenReturn(budgetAnthropicModel());
        when(models.runtimeModel("claude-traditional")).thenReturn(traditionalAnthropicModel());
        AnthropicRuntimeOptions options = new AnthropicRuntimeOptions(
                models, new AnthropicRuntimeProperties());

        return Stream.of(
                invalid("unknown key", "unknown", () ->
                        options.normalize("claude-adaptive", Map.of("unknown", true))),
                invalid("token type", "maxTokens", () ->
                        options.normalize("claude-adaptive", Map.of("maxTokens", "12000"))),
                invalid("fractional tokens", "maxTokens", () ->
                        options.normalize("claude-adaptive", Map.of("maxTokens", 1200.5))),
                invalid("token lower bound", "maxTokens", () ->
                        options.normalize("claude-adaptive", Map.of("maxTokens", 0))),
                invalid("token server limit", "maxTokens", () ->
                        options.normalize("claude-adaptive", Map.of("maxTokens", 16_001))),
                invalid("unsupported thinking mode", "thinking", () ->
                        options.normalize("claude-adaptive", Map.of("thinking", "enabled"))),
                invalid("unsupported thinking budget", "thinkingBudgetTokens", () ->
                        options.normalize("claude-adaptive", Map.of("thinkingBudgetTokens", 2_048))),
                invalid("budget lower bound", "thinkingBudgetTokens", () ->
                        options.normalize("claude-budget", Map.of("thinkingBudgetTokens", 1_023))),
                invalid("budget must fit selected max", "thinkingBudgetTokens", () ->
                        options.normalize("claude-budget", Map.of(
                                "thinking", "enabled", "maxTokens", 2_048,
                                "thinkingBudgetTokens", 2_048))),
                invalid("temperature lower bound", "temperature", () ->
                        options.normalize("claude-traditional", Map.of("temperature", -0.1))),
                invalid("temperature upper bound", "temperature", () ->
                        options.normalize("claude-traditional", Map.of("temperature", 1.1))),
                invalid("temperature finite", "temperature", () ->
                        options.normalize("claude-traditional", Map.of("temperature", Double.NaN))),
                invalid("parallel tool call type", "parallelToolCalls", () ->
                        options.normalize("claude-adaptive", Map.of("parallelToolCalls", "true")))
        ).map(this::dynamicTest);
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidOpenAiRuntimeOptions() {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        when(models.runtimeModel("gpt-reasoning")).thenReturn(reasoningOpenAiModel());
        when(models.runtimeModel("gpt-traditional")).thenReturn(traditionalOpenAiModel());
        OpenAiRuntimeOptions options = new OpenAiRuntimeOptions(models, new OpenAiRuntimeProperties());

        return Stream.of(
                invalid("unknown key", "unknown", () ->
                        options.normalize("gpt-reasoning", Map.of("unknown", true))),
                invalid("token type", "maxOutputTokens", () ->
                        options.normalize("gpt-reasoning", Map.of("maxOutputTokens", "8000"))),
                invalid("fractional tokens", "maxOutputTokens", () ->
                        options.normalize("gpt-reasoning", Map.of("maxOutputTokens", 8000.5))),
                invalid("token lower bound", "maxOutputTokens", () ->
                        options.normalize("gpt-reasoning", Map.of("maxOutputTokens", 0))),
                invalid("token server limit", "maxOutputTokens", () ->
                        options.normalize("gpt-reasoning", Map.of("maxOutputTokens", 16_001))),
                invalid("verbosity value", "verbosity", () ->
                        options.normalize("gpt-reasoning", Map.of("verbosity", "extreme"))),
                invalid("reasoning temperature", "temperature", () ->
                        options.normalize("gpt-reasoning", Map.of("temperature", 0.5))),
                invalid("temperature lower bound", "temperature", () ->
                        options.normalize("gpt-traditional", Map.of("temperature", -0.1))),
                invalid("temperature upper bound", "temperature", () ->
                        options.normalize("gpt-traditional", Map.of("temperature", 2.1))),
                invalid("temperature finite", "temperature", () ->
                        options.normalize("gpt-traditional", Map.of("temperature", Double.POSITIVE_INFINITY))),
                invalid("frequency penalty lower bound", "frequencyPenalty", () ->
                        options.normalize("gpt-traditional", Map.of("frequencyPenalty", -2.1))),
                invalid("presence penalty upper bound", "presencePenalty", () ->
                        options.normalize("gpt-traditional", Map.of("presencePenalty", 2.1))),
                invalid("parallel tool call type", "parallelToolCalls", () ->
                        options.normalize("gpt-reasoning", Map.of("parallelToolCalls", 1)))
        ).map(this::dynamicTest);
    }

    @Test
    void rejectsRuntimeOptionsForTheProviderNeutralDefaultRuntime() {
        SpringAIRuntime runtime = new SpringAIRuntime(null, null, null, null, null, null);

        assertThat(runtime.normalizeOptions("any-model", null)).isEmpty();
        assertThat(runtime.normalizeOptions("any-model", Map.of())).isEmpty();
        assertThatThrownBy(() -> runtime.normalizeOptions("any-model", Map.of("temperature", 0.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default")
                .hasMessageContaining("does not accept runtime options");
    }

    @Test
    void rejectsModelsFromTheWrongProviderBeforeAcceptingOptions() {
        ScoreAiModelRegistry models = mock(ScoreAiModelRegistry.class);
        when(models.runtimeModel("gpt-reasoning")).thenReturn(reasoningOpenAiModel());
        when(models.runtimeModel("claude-adaptive")).thenReturn(adaptiveAnthropicModel());
        ClaudeRuntime claude = new ClaudeRuntime(null, null, null, null,
                new AnthropicRuntimeOptions(models, new AnthropicRuntimeProperties()));
        OpenAIRuntime openAi = new OpenAIRuntime(null, null, null, null,
                new OpenAiRuntimeOptions(models, new OpenAiRuntimeProperties()));

        assertThatThrownBy(() -> claude.normalizeOptions("gpt-reasoning", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Anthropic model");
        assertThatThrownBy(() -> openAi.normalizeOptions("claude-adaptive", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OpenAI model");
    }

    private DynamicTest dynamicTest(InvalidCase invalidCase) {
        return DynamicTest.dynamicTest(invalidCase.name(), () ->
                assertThatThrownBy(invalidCase.action()::run)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(invalidCase.optionName()));
    }

    private InvalidCase invalid(String name, String optionName, ThrowingAction action) {
        return new InvalidCase(name, optionName, action);
    }

    private ScoreAiModelRegistry.RuntimeModel adaptiveAnthropicModel() {
        return runtimeModel("claude-adaptive", "claude-adaptive", "anthropic",
                16_000, null, null, true, "high",
                null, null, null, null, List.of(), null);
    }

    private ScoreAiModelRegistry.RuntimeModel budgetAnthropicModel() {
        return runtimeModel("claude-budget", "claude-budget", "anthropic",
                4_096, null, 2_048, false, "high",
                null, null, null, null, List.of(), null);
    }

    private ScoreAiModelRegistry.RuntimeModel traditionalAnthropicModel() {
        return runtimeModel("claude-traditional", "claude-traditional", "anthropic",
                4_096, 0.5, null, false, null,
                null, null, null, true, List.of(), null);
    }

    private ScoreAiModelRegistry.RuntimeModel reasoningOpenAiModel() {
        return runtimeModel("gpt-reasoning", "gpt-5.6-test", "azure-openai",
                16_000, null, null, false, null,
                true, null, true, false, List.of(), null);
    }

    private ScoreAiModelRegistry.RuntimeModel traditionalOpenAiModel() {
        return runtimeModel("gpt-traditional", "gpt-4-test", "openai",
                4_096, 0.7, null, false, null,
                false, null, false, true, List.of(), null);
    }

    private ScoreAiModelRegistry.RuntimeModel runtimeModel(
            String name, String model, String providerType, Integer maxTokens,
            Double temperature, Integer thinkingBudgetTokens, boolean adaptiveThinking,
            String outputEffort, Boolean reasoningModel, Boolean supportsOutputEffort,
            Boolean supportsVerbosity, Boolean supportsTemperature,
            List<String> thinkingModes, String defaultThinking) {
        return new ScoreAiModelRegistry.RuntimeModel(
                name, model, providerType, maxTokens, temperature, thinkingBudgetTokens,
                adaptiveThinking, outputEffort, null,
                List.of(new ScoreAiModelRegistry.ReasoningEffortDescriptor(
                        "high", "High", "High reasoning")),
                reasoningModel, supportsOutputEffort, supportsVerbosity, supportsTemperature,
                thinkingModes, defaultThinking);
    }

    private record InvalidCase(String name, String optionName, ThrowingAction action) {}

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}

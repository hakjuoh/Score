package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ConfiguredAiRuntimeMatrixTest {

    private static final Map<String, ExpectedModel> EXPECTED_MODELS = Map.ofEntries(
            Map.entry("claude-fable-5", anthropic("low", "medium", "high", "max")),
            Map.entry("claude-opus-4_8", anthropic("low", "medium", "high", "max")),
            Map.entry("claude-sonnet-5", anthropic("low", "medium", "high", "max")),
            Map.entry("claude-haiku-4_5", anthropic("default")),
            Map.entry("gpt-5_6-sol", openAi("low", "medium", "high", "xhigh")),
            Map.entry("gpt-5_6-terra", openAi("low", "medium", "high", "xhigh")),
            Map.entry("gpt-5_6-luna", openAi("low", "medium", "high", "xhigh"))
    );

    @Test
    void developmentConfigurationExactlyMatchesTheCoveredMatrix() throws IOException {
        ScoreAiProperties properties = developmentProperties();

        assertThat(properties.getModels()).containsOnlyKeys(EXPECTED_MODELS.keySet());
        EXPECTED_MODELS.forEach((modelName, expected) -> {
            ScoreAiProperties.Model configured = properties.getModels().get(modelName);
            assertThat(configured.getRuntimes()).as("runtimes for %s", modelName)
                    .containsExactly(expected.providerRuntime());
            assertThat(configured.getReasoningEfforts()).as("reasoning efforts for %s", modelName)
                    .extracting(ScoreAiProperties.ReasoningEffort::getName)
                    .containsExactlyElementsOf(expected.reasoningEfforts());
            assertThat(configured.getContextWindow()).as("context window for %s", modelName)
                    .isEqualTo(200000L);
            assertThat(configured.getContextBudget().getAutoCompactThresholdTokens())
                    .as("auto compact threshold for %s", modelName).isEqualTo(150000L);
            assertThat(configured.getContextBudget().getToolOutputTokenLimit())
                    .as("tool output limit for %s", modelName).isEqualTo(32000L);
        });
    }

    @TestFactory
    Stream<DynamicTest> resolvesEveryConfiguredModelRuntimeAndReasoningEffortCombination() throws IOException {
        ScoreAiModelRegistry registry = registry(developmentProperties());
        List<Combination> combinations = expectedCombinations();

        assertThat(combinations).hasSize(50);
        return combinations.stream().map(combination -> DynamicTest.dynamicTest(
                combination.modelName() + " / " + combination.runtime() + " / " + combination.reasoningEffort(),
                () -> {
                    assertThat(registry.resolveModelName(combination.modelName()))
                            .isEqualTo(combination.modelName());
                    assertThat(registry.resolveRuntime(combination.modelName(), combination.runtime()))
                            .isEqualTo(combination.runtime());
                    assertThat(registry.resolveReasoningEffort(
                            combination.modelName(), combination.reasoningEffort()))
                            .isEqualTo(combination.reasoningEffort());

                }));
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidReasoningEffortsForEveryConfiguredModel() throws IOException {
        ScoreAiModelRegistry registry = registry(developmentProperties());

        return EXPECTED_MODELS.keySet().stream().map(modelName -> DynamicTest.dynamicTest(modelName,
                () -> assertThatThrownBy(() -> registry.resolveReasoningEffort(modelName, "ultra-invalid"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(modelName)
                        .hasMessageContaining("ultra-invalid")));
    }

    @TestFactory
    Stream<DynamicTest> rejectsUnknownAndProviderIncompatibleRuntimesForEveryConfiguredModel()
            throws IOException {
        ScoreAiModelRegistry registry = registry(developmentProperties());

        return EXPECTED_MODELS.entrySet().stream().flatMap(entry -> {
            String modelName = entry.getKey();
            String incompatible = entry.getValue().providerRuntime().equals(ScoreAiModelRegistry.CLAUDE)
                    ? ScoreAiModelRegistry.OPENAI : ScoreAiModelRegistry.CLAUDE;
            return Stream.of(
                    DynamicTest.dynamicTest(modelName + " rejects unknown runtime", () ->
                            assertRuntimeRejected(registry, modelName, "unknown-runtime")),
                    DynamicTest.dynamicTest(modelName + " rejects " + incompatible + " runtime", () ->
                            assertRuntimeRejected(registry, modelName, incompatible))
            );
        });
    }

    @Test
    void rejectsAnUnknownModelBeforeResolvingItsRuntimeOrEffort() throws IOException {
        ScoreAiModelRegistry registry = registry(developmentProperties());

        assertThatThrownBy(() -> registry.resolveRuntime("missing-model", "default"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-model");
        assertThatThrownBy(() -> registry.resolveReasoningEffort("missing-model", "low"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-model");
    }

    private void assertRuntimeRejected(ScoreAiModelRegistry registry, String modelName, String runtime) {
        assertThatThrownBy(() -> registry.resolveRuntime(modelName, runtime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(modelName)
                .hasMessageContaining(runtime);
    }

    private List<Combination> expectedCombinations() {
        List<Combination> combinations = new ArrayList<>();
        EXPECTED_MODELS.forEach((modelName, model) ->
                Stream.of(ScoreAiModelRegistry.DEFAULT, model.providerRuntime()).forEach(runtime ->
                        model.reasoningEfforts().forEach(effort ->
                                combinations.add(new Combination(modelName, runtime, effort)))));
        return combinations;
    }

    private ScoreAiModelRegistry registry(ScoreAiProperties properties) {
        properties.getProviders().values().forEach(provider -> {
            provider.setKey("test-key");
            provider.setBaseUrl("https://provider.example");
        });
        Map<String, ChatModel> models = new LinkedHashMap<>();
        properties.getModels().keySet().forEach(name -> models.put(name, mock(ChatModel.class)));
        return new ScoreAiModelRegistry(properties, models);
    }

    private ScoreAiProperties developmentProperties() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load(
                        "application-dev", new ClassPathResource("application-dev.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("score.ai", ScoreAiProperties.class)
                .orElseThrow(() -> new IllegalStateException("score.ai configuration was not bound"));
    }

    private static ExpectedModel anthropic(String... efforts) {
        return new ExpectedModel(ScoreAiModelRegistry.CLAUDE, List.of(efforts));
    }

    private static ExpectedModel openAi(String... efforts) {
        return new ExpectedModel(ScoreAiModelRegistry.OPENAI, List.of(efforts));
    }

    private record ExpectedModel(String providerRuntime, List<String> reasoningEfforts) {}

    private record Combination(String modelName, String runtime, String reasoningEffort) {}
}

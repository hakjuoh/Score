package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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
import static org.mockito.Mockito.mock;

/** Guards the complete request options because Spring AI replaces, rather than merges, prompt options. */
class ConfiguredAiRuntimeRequestOptionsMatrixTest {

    @TestFactory
    Stream<DynamicTest> preservesProviderOptionsForEveryRuntimeModelAndReasoningCombination()
            throws IOException {
        ScoreAiProperties properties = developmentProperties();
        ScoreAiModelRegistry registry = registry(properties);
        AnthropicRuntimeOptions anthropic = new AnthropicRuntimeOptions(
                registry, new AnthropicRuntimeProperties());
        OpenAiRuntimeOptions openAi = new OpenAiRuntimeOptions(
                registry, new OpenAiRuntimeProperties());

        List<Combination> combinations = combinations(properties);
        assertThat(combinations).hasSize(50);
        return combinations.stream().map(combination -> DynamicTest.dynamicTest(
                combination.modelName() + " / " + combination.runtime() + " / " + combination.effort(),
                () -> {
                    ScoreAiModelRegistry.RuntimeModel model = registry.runtimeModel(combination.modelName());
                    if ("anthropic".equals(model.providerType())) {
                        var options = anthropic.options(model.name(), combination.effort(), Map.of());
                        assertThat(options.getModel()).isEqualTo(model.model());
                        assertThat(options.getMaxTokens()).isEqualTo(16_000);
                        assertThat(options.getCacheOptions().getStrategy())
                                .isEqualTo(AnthropicCacheStrategy.CONVERSATION_HISTORY);
                        assertThat(options.getCacheOptions().isMultiBlockSystemCaching()).isTrue();
                        assertThat(options.getCacheOptions().isCacheToolResults()).isTrue();
                        if ("claude-haiku-4_5".equals(model.name())) {
                            assertThat(options.getThinking()).isNotNull();
                            assertThat(options.getThinking().isDisabled()).isTrue();
                            assertThat(options.getOutputConfig()).isNull();
                        } else {
                            assertThat(options.getThinking()).isNotNull();
                            assertThat(options.getThinking().isAdaptive()).isTrue();
                            assertThat(options.getOutputConfig()).isNotNull();
                            assertThat(options.getOutputConfig().effort()).isPresent()
                                    .get().extracting(effort -> effort.asString())
                                    .isEqualTo(combination.effort());
                        }
                    } else {
                        OpenAiChatOptions options = openAi.options(
                                model.name(), combination.effort(), Map.of());
                        assertThat(options.getModel()).isEqualTo(model.model());
                        assertThat(options.getDeploymentName()).isEqualTo(model.model());
                        assertThat(options.isMicrosoftFoundry()).isTrue();
                        assertThat(options.getReasoningEffort()).isEqualTo(combination.effort());
                        assertThat(options.getTemperature()).isNull();
                        assertThat(options.getMaxTokens()).isNull();
                        assertThat(options.getStreamOptions()).isNotNull();
                        assertThat(options.getStreamOptions().includeUsage()).isTrue();
                    }
                }));
    }

    private List<Combination> combinations(ScoreAiProperties properties) {
        List<Combination> result = new ArrayList<>();
        properties.getModels().forEach((modelName, model) -> {
            String providerRuntime = model.getRuntimes().getFirst();
            for (String runtime : List.of(ScoreAiModelRegistry.DEFAULT, providerRuntime)) {
                model.getReasoningEfforts().forEach(effort ->
                        result.add(new Combination(modelName, runtime, effort.getName())));
            }
        });
        return result;
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

    private record Combination(String modelName, String runtime, String effort) {}
}

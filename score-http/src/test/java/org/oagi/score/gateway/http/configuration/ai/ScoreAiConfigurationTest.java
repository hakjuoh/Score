package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.oagi.score.gateway.http.api.ai_management.runtime.AnthropicRuntimeProperties;
import org.oagi.score.gateway.http.api.ai_management.runtime.OpenAiRuntimeProperties;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ScoreAiConfigurationTest {

    @Test
    void reloadsTheSystemPromptFromAnExternalFile(@TempDir Path tempDir) throws Exception {
        Path promptFile = Files.createTempFile(tempDir, "assistant-system-prompt", ".md");
        Files.writeString(promptFile, "First prompt: {pageContext}");
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getAssistant().setSystemPromptResource(promptFile.toUri().toString());

        ScoreAiSystemPrompt prompt = new ScoreAiConfiguration().scoreAiSystemPrompt(
                properties, new DefaultResourceLoader());

        assertEquals("First prompt: {pageContext}", prompt.text());
        Files.writeString(promptFile, "Updated prompt: {pageContext}");
        assertEquals("Updated prompt: {pageContext}", prompt.text());
    }

    @Test
    void configuresAzureOpenAiWithDeploymentAndApiVersion() {
        ScoreAiProperties properties = properties("gpt-5.6-sol", "azure-openai");
        ScoreAiProperties.Provider provider = properties.getProviders().get("azure-openai");
        provider.setType("azure-openai");
        provider.setBaseUrl("https://example.openai.azure.com/");
        provider.setKey("test-key");
        provider.setApiVersion("2024-10-21");
        properties.getModels().get("gpt-5.6-sol").setRuntimes(List.of("openai", "unknown-runtime"));

        Map<String, ChatModel> models = chatModels(properties);

        OpenAiResponsesChatModel model = assertInstanceOf(
                OpenAiResponsesChatModel.class, models.get("gpt-5.6-sol"));
        assertEquals("https://example.openai.azure.com", model.getOptions().getBaseUrl());
        assertEquals("gpt-5.6-sol", model.getOptions().getDeploymentName());
        assertEquals("gpt-5.6-sol", model.getOptions().getModel());
        assertEquals("2024-10-21", model.getOptions().getMicrosoftFoundryServiceVersion().value());
        assertTrue(model.getOptions().isMicrosoftFoundry());
        assertEquals("https://example.openai.azure.com/openai/v1",
                OpenAiResponsesChatModel.responsesBaseUrl(model.getOptions()));

        ScoreAiModelRegistry registry = new ScoreAiModelRegistry(properties, models);
        assertEquals(List.of("default", "openai"), registry.availableModels().getFirst().runtimes().stream()
                .map(ScoreAiModelRegistry.RuntimeDescriptor::name).toList());
        assertEquals(List.of("Default", "OpenAI"), registry.availableModels().getFirst().runtimes().stream()
                .map(ScoreAiModelRegistry.RuntimeDescriptor::displayName).toList());
        assertEquals("default", registry.resolveRuntime("gpt-5.6-sol", null));
        assertEquals("default", registry.resolveRuntime("gpt-5.6-sol", "spring-ai"));
        assertEquals("openai", registry.resolveRuntime("gpt-5.6-sol", "openai"));
        assertEquals("openai", registry.resolveRuntime("gpt-5.6-sol", "codex-sdk"));
    }

    @Test
    void configuresAzureFoundryEndpointAndModelWithoutReasoningEffort() {
        ScoreAiProperties properties = properties("claude-haiku-4_5", "azure-foundry");
        ScoreAiProperties.Provider provider = properties.getProviders().get("azure-foundry");
        provider.setType("anthropic");
        provider.setBaseUrl("https://example.services.ai.azure.com/");
        provider.setKey("test-key");
        ScoreAiProperties.Model configured = properties.getModels().get("claude-haiku-4_5");
        configured.setModel("claude-haiku-4-5");
        configured.setReasoningEffort("default");
        configured.setReasoningEfforts(List.of(reasoningEffort(
                "default", "Default", "Uses the model's built-in response behavior.")));
        configured.setRuntimes(List.of("claude"));

        Map<String, ChatModel> models = chatModels(properties);

        AnthropicChatModel model = assertInstanceOf(
                AnthropicChatModel.class, models.get("claude-haiku-4_5"));
        assertEquals("https://example.services.ai.azure.com/anthropic", model.getOptions().getBaseUrl());
        assertEquals("claude-haiku-4-5", model.getOptions().getModel());

        ScoreAiModelRegistry registry = new ScoreAiModelRegistry(properties, models);
        assertNull(model.getOptions().getOutputConfig());
        assertEquals("claude", registry.resolveRuntime("claude-haiku-4_5", "claude"));
        assertEquals("claude", registry.resolveRuntime("claude-haiku-4_5", "claude-sdk"));
    }

    @Test
    void bindsEveryDevelopmentModelToItsDeployment() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load(
                        "application-dev", new ClassPathResource("application-dev.yml"))
                .forEach(environment.getPropertySources()::addLast);

        ScoreAiProperties properties = Binder.get(environment)
                .bind("score.ai", ScoreAiProperties.class)
                .orElseThrow(() -> new IllegalStateException("score.ai configuration was not bound"));

        assertEquals(Set.of(
                        "claude-fable-5", "claude-opus-4_8", "claude-sonnet-5", "claude-haiku-4_5",
                        "gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna"),
                properties.getModels().keySet());
        assertEquals("claude-opus-4-8", properties.getModels().get("claude-opus-4_8").getModel());
        assertEquals("claude-haiku-4-5", properties.getModels().get("claude-haiku-4_5").getModel());
        assertEquals("default", properties.getModels().get("claude-haiku-4_5").getReasoningEffort());
        assertEquals("gpt-5.6-terra", properties.getModels().get("gpt-5_6-terra").getModel());
        assertEquals("gpt-5.6-luna", properties.getModels().get("gpt-5_6-luna").getModel());
        assertEquals(16000, properties.getModels().get("claude-fable-5").getMaxTokens());
        assertNull(properties.getModels().get("gpt-5_6-sol").getMaxTokens());
        assertEquals(200000L, properties.getModels().get("gpt-5_6-sol").getContextWindow());
        assertEquals(List.of("claude"),
                properties.getModels().get("claude-fable-5").getRuntimes());
        assertEquals(List.of("openai"),
                properties.getModels().get("gpt-5_6-sol").getRuntimes());
        assertEquals("classpath:prompts/connect-center-assistant-system-prompt.md",
                properties.getAssistant().getSystemPromptResource());
        Map.of(
                "claude-fable-5", "max",
                "claude-opus-4_8", "max",
                "claude-sonnet-5", "max",
                "gpt-5_6-sol", "xhigh",
                "gpt-5_6-terra", "xhigh",
                "gpt-5_6-luna", "xhigh"
        ).forEach((modelName, effortName) -> assertEquals("Max", properties.getModels().get(modelName)
                .getReasoningEfforts().stream()
                .filter(effort -> effortName.equals(effort.getName()))
                .findFirst().orElseThrow().getDisplayName()));
        assertTrue(properties.getModels().values().stream()
                .flatMap(model -> model.getReasoningEfforts().stream())
                .noneMatch(effort -> "Ultra Code".equals(effort.getDisplayName())));
    }

    @Test
    void omitsModelsWhoseProviderCredentialsAreNotConfigured() {
        ScoreAiProperties properties = properties("gpt-5.6-sol", "azure-openai");
        properties.getProviders().get("azure-openai").setType("azure-openai");

        assertTrue(chatModels(properties).isEmpty());
    }

    @Test
    void fallsBackToTheFirstAvailableModelWhenConfiguredDefaultIsUnavailable() {
        ScoreAiProperties properties = properties("gpt-5.6-sol", "azure-openai");
        properties.setModelName("claude-fable-5");
        ScoreAiProperties.Provider provider = properties.getProviders().get("azure-openai");
        provider.setBaseUrl("https://example.openai.azure.com");
        provider.setKey("test-key");

        ScoreAiModelRegistry registry = new ScoreAiModelRegistry(
                properties, Map.of("gpt-5.6-sol", mock(ChatModel.class)));

        assertTrue(registry.isAvailable());
        assertEquals("gpt-5.6-sol", registry.modelName());
        assertTrue(registry.availableModels().getFirst().defaultModel());
        assertEquals("GPT-5.6 SOL", registry.availableModels().getFirst().displayName());
        assertEquals("medium", registry.availableModels().getFirst().defaultReasoningEffort());
        assertEquals("default", registry.availableModels().getFirst().defaultRuntime());
        assertEquals(List.of("default"), registry.availableModels().getFirst().runtimes().stream()
                .map(ScoreAiModelRegistry.RuntimeDescriptor::name).toList());
        assertEquals(List.of("Low", "Medium", "High"), registry.availableModels().getFirst()
                .reasoningEfforts().stream().map(ScoreAiModelRegistry.ReasoningEffortDescriptor::displayName).toList());
    }

    @Test
    void rejectsAContextThresholdThatConsumesReservedOutputHeadroom() {
        ScoreAiProperties properties = properties("gpt-5.6-sol", "azure-openai");
        ScoreAiProperties.Provider provider = properties.getProviders().get("azure-openai");
        provider.setType("azure-openai");
        provider.setBaseUrl("https://example.openai.azure.com");
        provider.setKey("test-key");
        ScoreAiProperties.Model model = properties.getModels().get("gpt-5.6-sol");
        model.setContextWindow(1000L);
        model.getContextBudget().setOutputReserveTokens(400L);
        model.getContextBudget().setEmergencyHeadroomTokens(200L);
        model.getContextBudget().setAutoCompactThresholdTokens(401L);
        model.getContextBudget().setToolOutputTokenLimit(100L);

        assertThatThrownBy(() -> chatModels(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe input")
                .hasMessageContaining("gpt-5.6-sol");
    }

    private ScoreAiProperties properties(String modelName, String providerName) {
        ScoreAiProperties.Provider provider = new ScoreAiProperties.Provider();
        ScoreAiProperties.Model model = new ScoreAiProperties.Model();
        model.setDisplayName("GPT-5.6 SOL");
        model.setProvider(providerName);
        model.setModel(modelName);
        model.setReasoningEffort("medium");
        model.setReasoningEfforts(List.of(
                reasoningEffort("low", "Low", "Fast responses."),
                reasoningEffort("medium", "Medium", "Balanced reasoning."),
                reasoningEffort("high", "High", "Greater reasoning depth.")));

        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setProviders(new LinkedHashMap<>(Map.of(providerName, provider)));
        properties.setModels(new LinkedHashMap<>(Map.of(modelName, model)));
        properties.setModelName(modelName);
        return properties;
    }

    private Map<String, ChatModel> chatModels(ScoreAiProperties properties) {
        return new ScoreAiConfiguration().scoreAiChatModels(
                properties, new AnthropicRuntimeProperties(), new OpenAiRuntimeProperties());
    }

    private ScoreAiProperties.ReasoningEffort reasoningEffort(
            String name, String displayName, String description) {
        ScoreAiProperties.ReasoningEffort effort = new ScoreAiProperties.ReasoningEffort();
        effort.setName(name);
        effort.setDisplayName(displayName);
        effort.setDescription(description);
        return effort;
    }
}

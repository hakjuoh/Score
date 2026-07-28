package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.oagi.score.gateway.http.api.ai_management.agent.AssistantAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.tool.AiMutationToolGuard;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ScoreAiConfigurationTest {

    @Test
    void usesTheConnectCenterEntityAwareToolIndex() {
        ToolIndex index = new ScoreAiConfiguration().scoreAiToolIndex();

        assertThat(index).isInstanceOf(ScoreToolIndex.class);
    }

    @Test
    void promptResourcesUsePlaceholdersForExecutionProtocolValues() throws Exception {
        Resource[] prompts = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:ai/**/*.md");
        assertThat(prompts).isNotEmpty();
        for (Resource resource : prompts) {
            String text = resource.getContentAsString(StandardCharsets.UTF_8);
            assertThat(text).as(resource.getDescription())
                    .doesNotContain(AiMutationToolGuard.MUTATION_CONFIRMATION_REQUIRED,
                            AiMutationToolGuard.REQUEST_STOPPING);
        }
        String assistant = new ClassPathResource(
                "ai/system/system-prompt-connect-center-assistant.md")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(assistant)
                .contains("## Input", "Input interpretation rules:",
                        "## Output", "Workflow execution rules:", "Tool-use rules:",
                        "Capability disclosure rules:",
                        "Evidence and identity rules:", "Mutation and interruption rules:",
                        "Safety rules:", "separate request-scoped user-context block",
                        "${mutationConfirmationRequired}", "${mutationApprovalPolicy}",
                        "${requestStopping}", "Never retry silently",
                        "Never announce or imply that approval is required before making a tool call");
        assertThat(assistant).doesNotContain("${pageContext}", "## Request-scoped input");
        assertThat(new AiAgentCatalog(new DefaultResourceLoader())
                .configuredRootDefinition()
                .instruction().value())
                .startsWith("You are the connectCenter Assistant.")
                .doesNotContain("role:", "toolPolicy:");
    }

    @Test
    void assistantDisclosesOnlyCatalogBackedCapabilities() throws Exception {
        String assistant = new ClassPathResource(
                "ai/system/system-prompt-connect-center-assistant.md")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(assistant).contains(
                "`available-deferred-tools` catalog",
                "complete and authoritative capability surface",
                "only when at least one tool name in that catalog directly supports it",
                "only when the catalog contains the corresponding tool",
                "Never replace a partial operation set with a broad umbrella verb such as \"manage\"",
                "Group supported tools into concise user-facing categories",
                "If the catalog is absent or empty, do not enumerate capabilities");
    }

    @Test
    void gatewayHandsGreetingsAndCapabilityQuestionsToTheCatalogAwareAssistant()
            throws Exception {
        String gateway = new ClassPathResource("ai/system/system-prompt-gateway.md")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(gateway).contains(
                "DIRECT is allowed only for thanks",
                "Greetings and capability/help questions are HANDOFF",
                "requester-scoped tool catalog");
    }

    @Test
    void reloadsTheConfiguredRootAgentFromAnExternalFile(@TempDir Path tempDir) throws Exception {
        Path promptFile = Files.createTempFile(tempDir, "assistant-system-prompt", ".md");
        Files.writeString(promptFile, rootAgent("First prompt: ${pageContext}"));
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getAssistant().setSystemPromptResource(promptFile.toUri().toString());

        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader(), properties);
        AssistantAgent rootAgent = new AssistantAgent(catalog);

        assertEquals("external-root-agent", rootAgent.id().value());
        assertEquals("connectcenter-assistant", rootAgent.callId().value());
        assertEquals("First prompt: ${pageContext}", rootAgent
                .definition().instruction().value());
        assertEquals("First prompt: page ${literal}",
                rootAgent.definition().instruction()
                        .render(Map.of("pageContext", "page ${literal}")).value());
        assertThatThrownBy(() -> rootAgent.definition()
                .instruction().render(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageContext");
        Files.writeString(promptFile, rootAgent("Updated prompt: ${pageContext}"));
        assertEquals("Updated prompt: ${pageContext}", rootAgent
                .definition().instruction().value());
    }

    private String rootAgent(String instruction) {
        return """
                ---
                id: external-root-agent
                name: External root Agent
                description: Configured user-facing assistant used by the test.
                ---

                %s
                """.formatted(instruction);
    }

    @Test
    void configuresAzureOpenAiToUseTheResponsesEndpoint() {
        ScoreAiProperties properties = properties("gpt-5.6-sol", "azure-openai");
        ScoreAiProperties.Provider provider = properties.getProviders().get("azure-openai");
        provider.setType("azure-openai");
        provider.setBaseUrl("https://example.openai.azure.com/");
        provider.setKey("test-key");
        provider.setApiVersion("2024-10-21");

        Map<String, ChatModel> models = chatModels(properties);

        ScoreOpenAiResponsesChatModel model = assertInstanceOf(
                ScoreOpenAiResponsesChatModel.class, models.get("gpt-5.6-sol"));
        assertEquals("https://example.openai.azure.com", model.getOptions().getBaseUrl());
        assertEquals("https://example.openai.azure.com/openai/v1", model.responsesBaseUrl());
        assertEquals("gpt-5.6-sol", model.getOptions().getDeploymentName());
        assertEquals("gpt-5.6-sol", model.getOptions().getModel());
        assertTrue(model.getOptions().isMicrosoftFoundry());
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

        Map<String, ChatModel> models = chatModels(properties);

        AnthropicChatModel model = assertInstanceOf(
                AnthropicChatModel.class, models.get("claude-haiku-4_5"));
        assertEquals("https://example.services.ai.azure.com/anthropic", model.getOptions().getBaseUrl());
        assertEquals("claude-haiku-4-5", model.getOptions().getModel());

        assertNull(model.getOptions().getOutputConfig());
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
                        "claude-fable-5", "claude-opus-5", "claude-sonnet-5", "claude-haiku-4_5",
                        "gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna"),
                properties.getModels().keySet());
        assertEquals("claude-opus-5", properties.getModels().get("claude-opus-5").getModel());
        assertEquals("claude-haiku-4-5", properties.getModels().get("claude-haiku-4_5").getModel());
        assertEquals("default", properties.getModels().get("claude-haiku-4_5").getReasoningEffort());
        assertEquals("gpt-5.6-terra", properties.getModels().get("gpt-5_6-terra").getModel());
        assertEquals("gpt-5.6-luna", properties.getModels().get("gpt-5_6-luna").getModel());
        assertEquals(16000, properties.getModels().get("claude-fable-5").getMaxTokens());
        assertNull(properties.getModels().get("gpt-5_6-sol").getMaxTokens());
        assertEquals(200000L, properties.getModels().get("gpt-5_6-sol").getContextWindow());
        assertEquals("classpath:ai/system/system-prompt-connect-center-assistant.md",
                properties.getAssistant().getSystemPromptResource());
        assertNull(environment.getProperty("score.ai.gateway.model-name"));
        Map.of(
                "claude-fable-5", "max",
                "claude-opus-5", "max",
                "claude-sonnet-5", "max",
                "gpt-5_6-sol", "xhigh",
                "gpt-5_6-terra", "xhigh",
                "gpt-5_6-luna", "xhigh"
        ).forEach((modelName, effortName) -> assertEquals("Extra High", properties.getModels().get(modelName)
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
        assertEquals(List.of("Low", "Medium", "High"), registry.availableModels().getFirst()
                .reasoningEfforts().stream().map(ScoreAiModelRegistry.ReasoningEffortDescriptor::displayName).toList());
    }

    @Test
    void canonicalizesLegacyNoneAndDeduplicatesDisabledReasoningEffort() {
        ScoreAiProperties properties = properties("claude-sonnet-5", "azure-foundry");
        ScoreAiProperties.Provider provider = properties.getProviders().get("azure-foundry");
        provider.setType("anthropic");
        provider.setBaseUrl("https://example.services.ai.azure.com");
        provider.setKey("test-key");
        ScoreAiProperties.Model model = properties.getModels().get("claude-sonnet-5");
        model.setReasoningEfforts(List.of(
                reasoningEffort("none", "Legacy None", "Legacy disabled setting."),
                reasoningEffort("disabled", "Disabled", "Disable reasoning."),
                reasoningEffort("medium", "Medium", "Balanced reasoning.")));
        model.getModelCapabilities().setThinkingModes(List.of("ADAPTIVE", "DISABLED"));

        ScoreAiModelRegistry registry = new ScoreAiModelRegistry(
                properties, Map.of("claude-sonnet-5", mock(ChatModel.class)));

        assertEquals("disabled", registry.resolveReasoningEffort("claude-sonnet-5", "none"));
        assertEquals(1, registry.availableModels().getFirst().reasoningEfforts().stream()
                .filter(effort -> "disabled".equals(effort.name())).count());
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
                properties, new AnthropicChatProperties(), new OpenAiChatProperties());
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

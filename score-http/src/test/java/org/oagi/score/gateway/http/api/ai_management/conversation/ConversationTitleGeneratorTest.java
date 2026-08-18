package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.*;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationTitleGeneratorTest {

    @Test
    void sanitizeTitleEnforcesLengthAndStripsPrefixes() {
        // Normal summary
        assertThat(ConversationTitleGenerator.sanitizeTitle("Order Management Overview", "fallback"))
                .isEqualTo("Order Management Overview");

        // Prefix and quotes stripping
        assertThat(ConversationTitleGenerator.sanitizeTitle("Title: \"BIE Creation Guide\"", "fallback"))
                .isEqualTo("BIE Creation Guide");
        assertThat(ConversationTitleGenerator.sanitizeTitle("'Invoice Processing Details'", "fallback"))
                .isEqualTo("Invoice Processing Details");

        // Long title clamped to 240 chars
        String longTitle = "A".repeat(300);
        String sanitized = ConversationTitleGenerator.sanitizeTitle(longTitle, "fallback");
        assertThat(sanitized.length()).isEqualTo(240);
        assertThat(sanitized).endsWith("...");

        // Blank/null fallback
        assertThat(ConversationTitleGenerator.sanitizeTitle("", "Fallback Prompt"))
                .isEqualTo("Fallback Prompt");
        assertThat(ConversationTitleGenerator.sanitizeTitle(null, null))
                .isEqualTo("New conversation");
    }

    @Test
    void generatesTitleUsingLightweightModelViaAgentRunner() {
        AiModel model = new AiModel(new AiModel.ModelId("gpt-4o-mini"),
                new AiModel.ProviderId("openai"),
                AiModel.ModelCapabilities.TEXT_ONLY, AiModel.ContextWindow.UNKNOWN);
        AiModelCatalog catalog = mock(AiModelCatalog.class);
        when(catalog.require("gpt-4o-mini")).thenReturn(model);

        ScoreAiModelRegistry registry = mock(ScoreAiModelRegistry.class);
        when(registry.lightweightModelName()).thenReturn("gpt-4o-mini");

        AtomicReference<AgentInvocation> invocation = new AtomicReference<>();
        AgentExecutionService execution = request -> {
            invocation.set(request);
            AiMessage.Assistant response = new AiMessage.Assistant("Title: Purchase Order Generation");
            return new AgentRunResult(response, List.of(response), Optional.empty(),
                    new AgentRunResult.RunMetadata(request.session().agent().id(),
                            model.id(), null, Map.of()));
        };

        AgentRunner runner = new AgentRunner(execution, catalog, null, null, List.of());
        AiAgentCatalog agentCatalog = new AiAgentCatalog(new DefaultResourceLoader());
        ConversationTitleGenerator titler = new ConversationTitleGenerator(
                runner, registry, agentCatalog);

        ExecutionScope parentScope = new ExecutionScope("req-1", "conv-1", "user", 1L,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());

        String result = titler.generateTitle("Create a purchase order BIE",
                "Here is how to create a purchase order...", parentScope);

        assertThat(result).isEqualTo("Purchase Order Generation");
        assertThat(invocation.get()).isNotNull();
        assertThat(invocation.get().session().agent().id().value()).isEqualTo("conversation-titler");
        assertThat(invocation.get().scope().purpose()).isEqualTo(ExecutionScope.Purpose.CONVERSATION_TITLING);
        assertThat(invocation.get().session().tools().isEmpty()).isTrue();
    }
}

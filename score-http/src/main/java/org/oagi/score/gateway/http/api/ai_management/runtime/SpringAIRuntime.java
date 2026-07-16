package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiSystemPrompt;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationToolGuard;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Map;

/** Provider-neutral Spring AI runtime. */
@Component
public final class SpringAIRuntime extends AbstractSpringAIRuntime {

    private final AnthropicRuntimeOptions anthropicOptions;
    private final OpenAiRuntimeOptions openAiOptions;

    @Autowired
    public SpringAIRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                           ToolSearchToolCallingAdvisor toolSearchAdvisor,
                           ScoreAiSystemPrompt systemPrompt,
                           AiMutationToolGuard mutationGuard,
                           AiElicitationService elicitations,
                           AnthropicRuntimeOptions anthropicOptions,
                           OpenAiRuntimeOptions openAiOptions) {
        super(models, mcpClients, toolSearchAdvisor, systemPrompt, mutationGuard, elicitations);
        this.anthropicOptions = anthropicOptions;
        this.openAiOptions = openAiOptions;
    }

    SpringAIRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                    ToolSearchToolCallingAdvisor toolSearchAdvisor, ScoreAiSystemPrompt systemPrompt,
                    AnthropicRuntimeOptions anthropicOptions, OpenAiRuntimeOptions openAiOptions) {
        this(models, mcpClients, toolSearchAdvisor, systemPrompt, null, null,
                anthropicOptions, openAiOptions);
    }

    @Override
    public String name() {
        return ScoreAiModelRegistry.DEFAULT;
    }

    @Override
    protected ChatOptions requestOptions(Context context) {
        var request = context.request();
        String providerType = models().providerType(request.modelName());
        if ("anthropic".equals(providerType)) {
            return anthropicOptions.options(request.modelName(), request.reasoningEffort(), Map.of());
        }
        if ("openai".equals(providerType) || "azure-openai".equals(providerType)) {
            return openAiOptions.options(request.modelName(), request.reasoningEffort(), Map.of());
        }
        throw new IllegalArgumentException("Default runtime does not support provider: " + providerType);
    }
}

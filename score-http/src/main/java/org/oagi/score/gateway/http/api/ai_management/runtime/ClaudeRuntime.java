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

import java.util.List;
import java.util.Map;

/** Claude runtime backed by Spring AI's AnthropicChatModel and the Anthropic Java SDK. */
@Component
public final class ClaudeRuntime extends AbstractSpringAIRuntime {

    private final AnthropicRuntimeOptions options;

    @Autowired
    public ClaudeRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                         ToolSearchToolCallingAdvisor toolSearchAdvisor,
                         ScoreAiSystemPrompt systemPrompt,
                         AiMutationToolGuard mutationGuard,
                         AiElicitationService elicitations,
                         AiProviderRetryExecutor providerRetry,
                         AnthropicRuntimeOptions options) {
        super(models, mcpClients, toolSearchAdvisor, systemPrompt, mutationGuard, elicitations,
                providerRetry);
        this.options = options;
    }

    ClaudeRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                  ToolSearchToolCallingAdvisor toolSearchAdvisor, ScoreAiSystemPrompt systemPrompt,
                  AiMutationToolGuard mutationGuard, AnthropicRuntimeOptions options) {
        this(models, mcpClients, toolSearchAdvisor, systemPrompt, mutationGuard, null, null, options);
    }

    ClaudeRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                  ToolSearchToolCallingAdvisor toolSearchAdvisor, ScoreAiSystemPrompt systemPrompt,
                  AnthropicRuntimeOptions options) {
        this(models, mcpClients, toolSearchAdvisor, systemPrompt, null, null, null, options);
    }

    @Override
    public String name() {
        return ScoreAiModelRegistry.CLAUDE;
    }

    @Override
    public List<Setting> settings(String modelName) {
        return options.settings(modelName);
    }

    @Override
    public Map<String, Object> normalizeOptions(String modelName, Map<String, Object> requested) {
        return options.normalize(modelName, requested);
    }

    @Override
    protected ChatOptions requestOptions(Context context) {
        var request = context.request();
        return options.options(request.modelName(), request.reasoningEffort(), request.runtimeOptions());
    }
}

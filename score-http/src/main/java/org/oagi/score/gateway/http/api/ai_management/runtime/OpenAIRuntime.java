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

/** OpenAI runtime backed by the Responses API and the OpenAI Java library. */
@Component
public final class OpenAIRuntime extends AbstractSpringAIRuntime {

    private final OpenAiRuntimeOptions options;

    @Autowired
    public OpenAIRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                         ToolSearchToolCallingAdvisor toolSearchAdvisor,
                         ScoreAiSystemPrompt systemPrompt,
                         AiMutationToolGuard mutationGuard,
                         AiElicitationService elicitations,
                         AiProviderRetryExecutor providerRetry,
                         OpenAiRuntimeOptions options) {
        super(models, mcpClients, toolSearchAdvisor, systemPrompt, mutationGuard, elicitations,
                providerRetry);
        this.options = options;
    }

    OpenAIRuntime(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                  ToolSearchToolCallingAdvisor toolSearchAdvisor, ScoreAiSystemPrompt systemPrompt,
                  OpenAiRuntimeOptions options) {
        this(models, mcpClients, toolSearchAdvisor, systemPrompt, null, null, null, options);
    }

    @Override
    public String name() {
        return ScoreAiModelRegistry.OPENAI;
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

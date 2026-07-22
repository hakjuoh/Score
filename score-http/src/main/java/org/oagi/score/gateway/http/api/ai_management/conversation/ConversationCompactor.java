package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentFactory;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.CatalogBackedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.ResolvedAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Shared manual/automatic compaction implemented as a normal no-Tool Agent run. */
@Component("compactor-agent")
public final class ConversationCompactor extends CatalogBackedAgent {

    private final AgentExecutionService execution;
    private final SpringAiModelCatalog models;
    private final AgentOutputGuardrailChain outputGuardrails;
    private final AgentFactory agents = AgentFactory.binding();

    public ConversationCompactor(AgentExecutionService execution, SpringAiModelCatalog models,
                                 AgentOutputGuardrailChain outputGuardrails,
                                 AiAgentCatalog catalog) {
        super(catalog);
        this.execution = execution; this.models = models; this.outputGuardrails = outputGuardrails;
    }

    public String compact(String modelId, List<AiMessage> history, AiMessage.User request,
                          ExecutionScope parentScope) {
        ResolvedAgent agent = agents.create(definition(), models.require(modelId), ToolSet.empty());
        ExecutionScope scope = parentScope.withPurpose(ExecutionScope.Purpose.COMPACTION);
        var result = execution.execute(new AgentInvocation(null, agent, request, history, scope, null));
        var guarded = outputGuardrails.evaluate(new AgentOutputGuardrail.Request(
                AgentOutputGuardrail.Scope.INTERNAL, result.response(), scope,
                Map.of("feature", "compaction")));
        if (!guarded.allowed()) {
            throw new IllegalStateException("The compacted memory was not accepted by output policy.");
        }
        return guarded.output().content();
    }

}

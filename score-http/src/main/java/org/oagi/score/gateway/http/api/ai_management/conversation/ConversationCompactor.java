package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrails;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailHandlers;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRequestHandler;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Shared manual/automatic compaction implemented as a normal no-Tool Agent run. */
@Component("compactor-agent")
public final class ConversationCompactor {

    private final AgentRunner runner;
    private final AgentOutputGuardrailChain outputGuardrails;
    private final ScoreAiObservability observability;
    private final AgentDefinition baseDefinition;

    @Autowired
    public ConversationCompactor(AgentRunner runner,
                                 AgentOutputGuardrailChain outputGuardrails,
                                 AiAgentCatalog catalog,
                                 ScoreAiObservability observability) {
        this.runner = runner;
        this.outputGuardrails = outputGuardrails;
        this.observability = observability;
        this.baseDefinition = catalog.systemDefinition("compactor-agent");
    }

    ConversationCompactor(AgentRunner runner, AgentOutputGuardrailChain outputGuardrails,
                          AiAgentCatalog catalog) {
        this(runner, outputGuardrails, catalog, ScoreAiObservability.noop());
    }

    public AgentOutput compact(String modelId, List<AiMessage> history, AiMessage.User request,
                               ExecutionScope parentScope,
                               AgentOutputGuardrail.Scope outputScope) {
        ExecutionScope scope = parentScope.withPurpose(ExecutionScope.Purpose.COMPACTION);
        ChatExecutionContext execution = ChatExecutionContext.standalone(
                scope.requestId(), scope.conversationId(), baseDefinition.id().value(), modelId,
                request, null, scope.purpose());
        AgentWorkflowContext workflow = AgentWorkflowContext.root(execution,
                new AgentWorkflowContext.Request(scope.requestId(), scope.conversationId(),
                        scope.requesterId(), modelId, request.content(), false, false,
                        1, "balanced", null, false, false), 1);
        Agent agent = new DefinedAgent(new AgentDefinition(baseDefinition.id(),
                baseDefinition.name(), baseDefinition.description(), baseDefinition.instruction(),
                requestHandler(modelId, history, request, scope),
                (ignored, context) -> new org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding(
                        org.oagi.score.gateway.http.api.ai_management.tool.ToolSet.empty(),
                        ToolExecutionGateway.disabled()),
                AgentResponseHandler.complete(), guardrails(outputScope), false));
        AgentDecision decision = runner.run(agent, workflow);
        if (!(decision instanceof AgentDecision.Complete complete)) {
            throw new IllegalStateException("Compaction Agent did not return a completed result.");
        }
        return complete.result();
    }

    private AgentRequestHandler requestHandler(String modelId, List<AiMessage> history,
                                               AiMessage.User request, ExecutionScope scope) {
        return (agent, context) -> new AgentRunRequest.Model(modelId,
                baseDefinition.instruction().render(), request, history, scope,
                Map.of("feature", "compaction"));
    }

    private AgentGuardrails guardrails(AgentOutputGuardrail.Scope scope) {
        return new AgentGuardrails(List.of(), List.of(AgentGuardrailHandlers.output(
                outputGuardrails, observability, scope,
                "compaction_output", Map.of("feature", "compaction"))), scope);
    }
}

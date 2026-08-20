package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * User-facing Agent definition.
 *
 * <p>The definition supplies the request policy for the shared
 * {@code AgentRunner}; it does not execute the chat model itself.</p>
 */
@Component("connectcenter-assistant")
public final class AssistantAgent implements Agent {

    public static final Agent.AgentId ASSISTANT_ID = new Agent.AgentId("connectcenter-assistant");
    /** @deprecated use {@link PlannerAgent#PLANNER_ID}. */
    @Deprecated(forRemoval = false)
    public static final Agent.AgentId PLANNER_ID = PlannerAgent.PLANNER_ID;
    private final AiAgentCatalog agents;
    private final AgentOutputGuardrailChain outputGuardrails;
    private final ScoreAiObservability observability;

    public AssistantAgent(AiAgentCatalog agents) {
        this(agents, null, ScoreAiObservability.noop());
    }

    @Autowired
    public AssistantAgent(AiAgentCatalog agents, AgentOutputGuardrailChain outputGuardrails,
                          ScoreAiObservability observability) {
        this.agents = agents;
        this.outputGuardrails = outputGuardrails;
        this.observability = observability;
    }

    @Override
    public AgentDefinition definition() {
        AgentDefinition configured = agents.configuredRootDefinition();
        return new AgentDefinition(configured.id(), configured.name(), configured.description(),
                configured.instruction(), this::prepare, AgentToolHandler.transport(),
                AgentResponseHandler.complete(), AgentGuardrailHandlers.publicOutput(
                        outputGuardrails, observability, "assistant_output",
                        Map.of("agent_id", ASSISTANT_ID.value())), false);
    }

    /** Exposes the configured definition without making the Agent an executor. */
    public AgentDefinition configuredDefinition() {
        return definition();
    }

    public Agent.Instruction instruction(Map<String, ?> parameters) {
        return definition().instruction().render(parameters);
    }

    @Override
    public Agent.AgentId callId() {
        return ASSISTANT_ID;
    }

    private AgentRunRequest prepare(Agent agent, AgentWorkflowContext context) {
        if (context.request().delegationRequested()
                || context.request().explicitDelegationRequested()) {
            return new AgentRunRequest.Skip(
                    new AgentDecision.Handoff(PlannerAgent.PLANNER_ID));
        }
        return new AgentRunRequest.Chat(context.execution().withWorkflowObservationContext(
                context.observationContext()));
    }
}

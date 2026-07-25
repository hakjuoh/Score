package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInstructions;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.agent.AssignedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrails;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailHandlers;
import org.oagi.score.gateway.http.api.ai_management.agent.AssignedAgentHandlers;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Address book for immutable Agent definitions; it does not execute a definition. */
final class AgentDirectory {

    private final AiAgentCatalog catalog;
    private final AgentInstructions instructions;
    private final AgentGuardrails assignedGuardrails;
    /** Copy-on-write snapshots publish every identity and call alias atomically. */
    private final AtomicReference<Map<Agent.AgentId, Agent>> definitions =
            new AtomicReference<>(Map.of());

    AgentDirectory(AiAgentCatalog catalog, AgentInstructions instructions,
                   List<? extends Agent> agents) {
        this(catalog, instructions, agents, null, ScoreAiObservability.noop());
    }

    AgentDirectory(AiAgentCatalog catalog, AgentInstructions instructions,
                   List<? extends Agent> agents, AgentOutputGuardrailChain outputGuardrails,
                   ScoreAiObservability observability) {
        this.catalog = catalog;
        this.instructions = instructions;
        this.assignedGuardrails = AgentGuardrailHandlers.publicOutput(outputGuardrails,
                observability, "assigned_agent_output", Map.of("agent_type", "worker"));
        if (agents != null) agents.stream().filter(Objects::nonNull).forEach(this::install);
    }

    Agent resolve(Agent.AgentId id) {
        Agent agent = definitions.get().get(id);
        if (agent == null) throw new IllegalArgumentException("Unknown Agent: " + id.value());
        return agent;
    }

    Agent role(String id) {
        return definitions.get().get(new Agent.AgentId(id));
    }

    boolean isEmpty() {
        return definitions.get().isEmpty();
    }

    boolean assignable(String id) {
        Agent installed = definitions.get().get(new Agent.AgentId(id));
        if (installed != null) return installed.definition().assignable();
        if (catalog == null) throw new IllegalArgumentException("Unknown assigned Agent: " + id);
        catalog.workerDefinition(id);
        return true;
    }

    synchronized Agent assigned(AiWorkflowPlan.AgentTask task) {
        Objects.requireNonNull(task, "task");
        Agent.AgentId target = new Agent.AgentId(task.agentId());
        Agent installed = definitions.get().get(target);
        if (installed != null) {
            if (!installed.definition().assignable()) {
                throw new IllegalArgumentException("Agent is not assignable: " + task.agentId());
            }
            return installed;
        }
        if (catalog == null || instructions == null) {
            throw new IllegalArgumentException("Unknown assigned Agent: " + task.agentId());
        }
        Agent assigned = createAssigned(task);
        if (!assigned.definition().assignable()) {
            throw new IllegalArgumentException("Agent is not assignable: " + task.agentId());
        }
        publish(assigned, List.of(target, assigned.id(), assigned.callId()));
        return assigned;
    }

    private Agent createAssigned(AiWorkflowPlan.AgentTask task) {
        AgentDefinition definition = catalog.workerDefinition(task.agentId());
        AssignedAgentHandlers handlers = new AssignedAgentHandlers(instructions);
        return new AssignedAgent(definition, handlers.requestHandler(),
                handlers.responseHandler(), assignedGuardrails);
    }

    private synchronized void install(Agent agent) {
        publish(agent, List.of(agent.id(), agent.callId()));
    }

    private void publish(Agent agent, List<Agent.AgentId> addresses) {
        Map<Agent.AgentId, Agent> current = definitions.get();
        for (Agent.AgentId address : addresses) {
            Agent previous = current.get(address);
            if (previous != null && previous != agent) {
                throw new IllegalArgumentException(
                        "Duplicate Agent address: " + address.value());
            }
        }
        Map<Agent.AgentId, Agent> updated = new LinkedHashMap<>(current);
        addresses.forEach(address -> updated.put(address, agent));
        definitions.set(Map.copyOf(updated));
    }
}

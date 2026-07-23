package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInstructions;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.agent.AssignedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves trusted control addresses and creates only catalog-backed or opted-in workers. */
final class WorkflowAgentRegistry {

    private final Map<Agent.AgentId, WorkflowAgent> installed;
    private final AiAgentCatalog catalog;
    private final AiChatExecutor executor;
    private final AgentInstructions instructions;

    WorkflowAgentRegistry(AiChatExecutor executor, AiAgentCatalog catalog,
                          AgentInstructions instructions,
                          List<? extends WorkflowAgent> agents) {
        this.executor = executor;
        this.catalog = catalog;
        this.instructions = instructions;
        Map<Agent.AgentId, WorkflowAgent> indexed = new LinkedHashMap<>();
        if (agents != null) {
            for (WorkflowAgent agent : agents) {
                if (agent == null) continue;
                if (indexed.putIfAbsent(agent.callId(), agent) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate Workflow Agent address: " + agent.callId().value());
                }
            }
        }
        this.installed = Map.copyOf(indexed);
    }

    boolean isEmpty() {
        return installed.isEmpty();
    }

    WorkflowAgent role(String id) {
        return installed.get(new Agent.AgentId(id));
    }

    WorkflowAgent resolve(Agent.AgentId target) {
        WorkflowAgent exact = installed.get(target);
        if (exact != null) return exact;
        throw new IllegalArgumentException("Unknown Workflow Agent: " + target.value());
    }

    boolean assignable(String agentId) {
        WorkflowAgent custom = installed.get(new Agent.AgentId(agentId));
        if (custom != null) return custom.assignable();
        if (catalog == null) {
            throw new IllegalArgumentException("Unknown assigned Agent: " + agentId);
        }
        catalog.workerDefinition(agentId);
        return true;
    }

    WorkflowAgent assigned(AiWorkflowPlan.AgentTask task) {
        WorkflowAgent custom = installed.get(new Agent.AgentId(task.agentId()));
        if (custom != null) {
            if (!custom.assignable()) {
                throw new IllegalArgumentException(
                        "Agent is not assignable: " + task.agentId());
            }
            return custom;
        }
        if (catalog == null || instructions == null) {
            throw new IllegalArgumentException("Unknown assigned Agent: " + task.agentId());
        }
        AgentDefinition definition = catalog.workerDefinition(task.agentId());
        return new AssignedAgent(definition, executor, instructions);
    }
}

package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInstructions;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinedAgent;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDirectoryTest {

    @Test
    void concurrentLazyAssignmentsShareOneDefinitionInstance() throws Exception {
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AgentDefinition base = new AgentDefinition(new Agent.AgentId("researcher"),
                "Researcher", "Finds evidence",
                new AgentDefinition.InstructionTemplate("Research safely."));
        when(catalog.workerDefinition("researcher")).thenReturn(base);
        AgentDirectory directory = new AgentDirectory(catalog, mock(AgentInstructions.class), List.of());
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "researcher", "Research", "Verify the current record.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Agent>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) jobs.add(() -> directory.assigned(task));
            List<Future<Agent>> futures = pool.invokeAll(jobs);
            Agent first = futures.getFirst().get();
            for (Future<Agent> future : futures) assertThat(future.get()).isSameAs(first);
            assertThat(directory.resolve(new Agent.AgentId("researcher"))).isSameAs(first);
            verify(catalog, times(1)).workerDefinition("researcher");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void publishesRequestedAliasAndCanonicalDefinitionInOneSnapshot() throws Exception {
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AgentDefinition base = new AgentDefinition(new Agent.AgentId("researcher"),
                "Researcher", "Finds evidence",
                new AgentDefinition.InstructionTemplate("Research safely."));
        when(catalog.workerDefinition("researcher-alias")).thenReturn(base);
        AgentDirectory directory = new AgentDirectory(
                catalog, mock(AgentInstructions.class), List.of());
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "researcher-alias", "Research", "Verify the current record.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Agent>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) jobs.add(() -> directory.assigned(task));
            List<Future<Agent>> futures = pool.invokeAll(jobs);
            Agent first = futures.getFirst().get();

            for (Future<Agent> future : futures) assertThat(future.get()).isSameAs(first);
            assertThat(directory.resolve(new Agent.AgentId("researcher-alias"))).isSameAs(first);
            assertThat(directory.resolve(new Agent.AgentId("researcher"))).isSameAs(first);
            verify(catalog, times(1)).workerDefinition("researcher-alias");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aliasCollisionDoesNotPublishAPartialDefinition() {
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AgentDefinition canonical = new AgentDefinition(new Agent.AgentId("researcher"),
                "Researcher", "Installed definition",
                new AgentDefinition.InstructionTemplate("Installed."));
        Agent installed = new DefinedAgent(canonical);
        AgentDefinition conflicting = new AgentDefinition(new Agent.AgentId("researcher"),
                "Conflicting", "Conflicting definition",
                new AgentDefinition.InstructionTemplate("Conflicting."));
        when(catalog.workerDefinition("researcher-alias")).thenReturn(conflicting);
        AgentDirectory directory = new AgentDirectory(catalog,
                mock(AgentInstructions.class), List.of(installed));
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "researcher-alias", "Research", "Verify the current record.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);

        assertThatThrownBy(() -> directory.assigned(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate Agent address: researcher");
        assertThat(directory.resolve(new Agent.AgentId("researcher"))).isSameAs(installed);
        assertThat(directory.role("researcher-alias")).isNull();
    }
}

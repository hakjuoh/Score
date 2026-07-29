package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerAgentTest {

    @Test
    void runnerUsesThePlannerDefinitionToCreateAValidatedRecursiveWorkflow() {
        Fixture fixture = fixture("""
                {
                  "root": {
                    "id": "root",
                    "members": [
                      {
                        "id": "research",
                        "agent": {
                          "agentId": "evidence-researcher",
                          "label": "Research",
                          "instruction": "Find the current evidence.",
                          "activeVerb": "Researching",
                          "completedVerb": "Researched",
                          "toolAccess": "READ_ONLY"
                        },
                        "workflow": null
                      },
                      {
                        "id": "review-group",
                        "agent": null,
                        "workflow": {
                          "id": "review",
                          "members": [
                            {
                              "id": "review",
                              "agent": {
                                "agentId": "critical-reviewer",
                                "label": "Review",
                                "instruction": "Independently verify the evidence.",
                                "activeVerb": "Reviewing",
                                "completedVerb": "Reviewed",
                                "toolAccess": "READ_ONLY"
                              },
                              "workflow": null
                            }
                          ]
                        }
                      }
                    ]
                  },
                  "guideMessage": "Delegating.",
                  "synthesisGuideMessage": "Combining results."
                }
                """);

        AgentDecision decision = fixture.runner.run(fixture.planner.callId(), fixture.context);

        AiWorkflowPlan plan = ((AgentDecision.Delegate) decision).workflow();
        assertThat(plan.root().members()).hasSize(2);
        assertThat(plan.root().members().getFirst().agent().agentId())
                .isEqualTo("evidence-researcher");
        assertThat(plan.root().members().getLast().workflow().members().getFirst()
                .agent().agentId()).isEqualTo("critical-reviewer");
        assertThat(plan.toString()).doesNotContain("parallel", "routing", "chain");
    }

    @Test
    void malformedPlannerOutputUsesTheBoundedFallbackDefinedByTheAgent() {
        Fixture fixture = fixture("not-json");

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.runner.run(fixture.planner.callId(), fixture.context)).workflow();

        assertThat(plan.root().members()).hasSize(2)
                .allSatisfy(member -> assertThat(member.agent().agentId())
                        .isEqualTo("evidence-researcher"));
        assertThat(plan.root().edges()).isEmpty();
        assertThat(plan.root().members()).allSatisfy(member ->
                assertThat(plan.root().predecessors(member.id())).isEmpty());
        verify(fixture.recorder).lifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_plan_fallback"), any(), any());
    }

    @Test
    void plannerProviderFailureFallsBackToAllRequestedAgentsInParallel() {
        Fixture fixture = fixture("unused", 3);
        when(fixture.execution.execute(any()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.runner.run(fixture.planner.callId(), fixture.context)).workflow();

        assertThat(plan.root().members()).hasSize(3);
        assertThat(plan.root().edges()).isEmpty();
        assertThat(plan.root().members()).allSatisfy(member ->
                assertThat(plan.root().predecessors(member.id())).isEmpty());
    }

    @Test
    void cancellationIsNotConvertedIntoAPlannerFallback() {
        Fixture fixture = fixture("unused");
        when(fixture.execution.execute(any()))
                .thenThrow(new CancellationException("stopped"));

        assertThatThrownBy(() -> fixture.runner.run(fixture.planner.callId(), fixture.context))
                .isInstanceOf(CancellationException.class);
    }

    private Fixture fixture(String modelOutput) {
        return fixture(modelOutput, 2);
    }

    private Fixture fixture(String modelOutput, int maximumAgents) {
        AgentExecutionService execution = mock(AgentExecutionService.class);
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        AiAgentCatalog catalog = mock(AiAgentCatalog.class);
        AgentDefinition plannerDefinition = new AgentDefinition(
                new Agent.AgentId("workflow-planner"), "Planner", "Plans work",
                new AgentDefinition.InstructionTemplate("Plan the request."));
        AiAgentDefinition researcher = new AiAgentDefinition(
                "evidence-researcher", "Researcher", "Finds evidence", "Research safely.");
        AiAgentDefinition reviewer = new AiAgentDefinition(
                "critical-reviewer", "Reviewer", "Checks evidence", "Review safely.");
        when(catalog.systemDefinition("workflow-planner")).thenReturn(plannerDefinition);
        when(catalog.workers()).thenReturn(List.of(researcher, reviewer));
        when(catalog.requireWorker("evidence-researcher")).thenReturn(researcher);
        when(catalog.requireWorker("critical-reviewer")).thenReturn(reviewer);
        when(catalog.defaultAgent()).thenReturn(researcher);
        AiModel model = new AiModel(new AiModel.ModelId("model"),
                new AiModel.ProviderId("test"), AiModel.ModelCapabilities.TEXT_ONLY,
                AiModel.ContextWindow.UNKNOWN);
        when(models.require("model")).thenReturn(model);
        AgentRunResult result = new AgentRunResult(new AiMessage.Assistant(modelOutput),
                List.of(), Optional.empty(), AgentRunResult.RunMetadata.empty());
        when(execution.execute(any())).thenReturn(result);
        PlannerAgent planner = new PlannerAgent(catalog, new ObjectMapper());
        AgentRunner runner = new AgentRunner(execution, models, catalog, null,
                List.of(planner));
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        AgentWorkflowContext.Request request = new AgentWorkflowContext.Request(
                "request-1", "conversation-1", "user-1", "model", "Investigate it",
                false, false, maximumAgents, "verification", "agents", true, false);
        ChatRequest chatRequest = new ChatRequest("Investigate it", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "verification", "agents");
        AgentWorkflowContext context = AgentWorkflowContext.root(
                ChatExecutionContext.fromRequest(chatRequest, List.of(),
                        new org.springframework.ai.chat.messages.UserMessage("Investigate it"),
                        null, recorder, false, false), request, 3);
        return new Fixture(planner, runner, context, recorder, execution);
    }

    private record Fixture(PlannerAgent planner, AgentRunner runner,
                           AgentWorkflowContext context, AiTrajectoryRecorder recorder,
                           AgentExecutionService execution) { }
}

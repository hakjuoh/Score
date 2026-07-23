package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;

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
    void createsAValidatedRecursiveWorkflowWithoutAnExecutionPatternType() {
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

        AgentDecision decision = fixture.planner.execute(fixture.context);

        AiWorkflowPlan plan = ((AgentDecision.Delegate) decision).workflow();
        assertThat(plan.root().members()).hasSize(2);
        assertThat(plan.root().members().getFirst().agent().agentId())
                .isEqualTo("evidence-researcher");
        assertThat(plan.root().members().getLast().workflow().members().getFirst()
                .agent().agentId()).isEqualTo("critical-reviewer");
        assertThat(plan.toString()).doesNotContain("parallel", "routing", "chain");
    }

    @Test
    void fallsBackToABoundedAgentQueueWhenTheModelOutputIsMalformed() {
        Fixture fixture = fixture("not-json");

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.planner.execute(fixture.context)).workflow();

        assertThat(plan.root().members()).hasSize(2)
                .allSatisfy(member -> assertThat(member.agent().agentId())
                        .isEqualTo("evidence-researcher"));
        verify(fixture.recorder).lifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_plan_fallback"), any(), any());
    }

    @Test
    void propagatesCancellationInsteadOfCreatingAFallbackPlan() {
        Fixture fixture = fixture("unused");
        when(fixture.execution.execute(any()))
                .thenThrow(new CancellationException("stopped"));

        assertThatThrownBy(() -> fixture.planner.execute(fixture.context))
                .isInstanceOf(CancellationException.class);
    }

    private Fixture fixture(String modelOutput) {
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
        when(models.require("model")).thenReturn(new AiModel(
                new AiModel.ModelId("model"), new AiModel.ProviderId("test"),
                AiModel.ModelCapabilities.TEXT_ONLY, AiModel.ContextWindow.UNKNOWN));
        AgentRunResult result = new AgentRunResult(new AiMessage.Assistant(modelOutput),
                List.of(), Optional.empty(), AgentRunResult.RunMetadata.empty());
        when(execution.execute(any())).thenReturn(result);
        PlannerAgent planner = new PlannerAgent(execution, models, catalog, new ObjectMapper());
        AiChatExecutor.Context legacy = mock(AiChatExecutor.Context.class);
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(legacy.history()).thenReturn(List.of());
        when(legacy.recorder()).thenReturn(recorder);
        when(legacy.guardrailDecisionIds()).thenReturn(List.of());
        AgentWorkflowContext.Request request = new AgentWorkflowContext.Request(
                "request-1", "conversation-1", "user-1", "model", "Investigate it",
                false, false, 2, "verification", "agents", true, false);
        AgentWorkflowContext context = AgentWorkflowContext.root(legacy, request, 3);
        return new Fixture(planner, context, recorder, execution);
    }

    private record Fixture(PlannerAgent planner, AgentWorkflowContext context,
                           AiTrajectoryRecorder recorder,
                           AgentExecutionService execution) { }
}

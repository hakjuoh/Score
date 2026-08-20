package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowType;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowIntent;

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
                          "guideMessage": "I’m finding the current evidence for you.",
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
                                "guideMessage": "I’m independently reviewing the evidence for you.",
                                "activeVerb": "Reviewing",
                                "completedVerb": "Reviewed",
                                "toolAccess": "READ_ONLY"
                              },
                              "workflow": null
                            }
                          ],
                          "edges": []
                        }
                      }
                    ],
                    "edges": []
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
        assertThat(plan.root().edges()).isEmpty();
        assertThat(plan.toString()).doesNotContain("parallel", "routing", "chain");
    }

    @Test
    void preservesOutcomeBasedCheckpointPhasesAndTheirDependency() {
        Fixture fixture = fixture("""
                {
                  "root": {
                    "id": "root",
                    "members": [
                      {
                        "id": "establish-state",
                        "agent": {
                          "agentId": "evidence-researcher",
                          "label": "Establish requested state",
                          "instruction": "Create the requested foundational state and read it back to verify completion.",
                          "guideMessage": "I’m establishing and checking the requested foundation.",
                          "activeVerb": "Establishing",
                          "completedVerb": "Established",
                          "toolAccess": "FULL"
                        },
                        "workflow": null
                      },
                      {
                        "id": "extend-state",
                        "agent": {
                          "agentId": "evidence-researcher",
                          "label": "Extend verified state",
                          "instruction": "Use the established-state result, verify the saved state with Tools, then apply and validate the dependent configuration.",
                          "guideMessage": "I’m verifying the saved foundation before applying the dependent configuration.",
                          "activeVerb": "Configuring",
                          "completedVerb": "Configured",
                          "toolAccess": "FULL"
                        },
                        "workflow": null
                      }
                    ],
                    "edges": [{"from":"establish-state","to":"extend-state"}]
                  },
                  "guideMessage": "I’m handling the request in two verified stages.",
                  "synthesisGuideMessage": "I’m confirming the results from both stages."
                }
                """, 2, "Establish a resource, then configure and validate its dependent behavior.");

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.runner.run(fixture.planner.callId(), fixture.context)).workflow();

        assertThat(plan.root().members()).hasSize(2);
        assertThat(plan.root().members()).extracting(member -> member.agent().agentId())
                .containsExactly("evidence-researcher", "evidence-researcher");
        assertThat(plan.root().predecessors("extend-state"))
                .containsExactly("establish-state");
        assertThat(plan.root().members().getLast().agent().instruction())
                .contains("verify the saved state with Tools");
    }

    @Test
    void plannerOutputWithoutExplicitDependencyEdgesUsesTheBoundedFallback() {
        Fixture fixture = fixture("""
                {
                  "root": {
                    "id": "root",
                    "members": [{
                      "id": "research",
                      "agent": {
                        "agentId": "evidence-researcher",
                        "label": "Research",
                        "instruction": "Find the current evidence.",
                        "toolAccess": "READ_ONLY"
                      },
                      "workflow": null
                    }]
                  }
                }
                """);

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.runner.run(fixture.planner.callId(), fixture.context)).workflow();

        assertThat(plan.root().members()).hasSize(2);
        assertThat(plan.root().edges()).isEmpty();
        verify(fixture.recorder).lifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_plan_fallback"), any(), any());
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
        assertThat(plan.root().members()).allSatisfy(member ->
                assertThat(member.agent().activeVerb()).isNull());
        verify(fixture.recorder).lifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_plan_fallback"), any(), any());
    }

    @Test
    void missingGuidesUseDeterministicFallbackText() {
        Fixture fixture = fixture("""
                {
                  "root": {
                    "id": "root",
                    "members": [{
                      "id": "research",
                      "agent": {
                        "agentId": "evidence-researcher",
                        "label": "Research",
                        "instruction": "Find current evidence.",
                        "guideMessage": null,
                        "toolAccess": "READ_ONLY"
                      },
                      "workflow": null
                    }],
                    "edges": []
                  },
                  "guideMessage": null,
                  "synthesisGuideMessage": null
                }
                """, 2, "Inspect the release components");

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.runner.run(fixture.planner.callId(), fixture.context)).workflow();

        assertThat(plan.guideMessage())
                .isEqualTo("I’m checking the request from the necessary perspectives.");
        assertThat(plan.synthesisGuideMessage())
                .isEqualTo("I’m combining the findings into one answer.");
        assertThat(plan.root().members()).allSatisfy(member ->
                assertThat(member.agent().guideMessage())
                        .isEqualTo("I’m independently checking the evidence for your request."));
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
    void explicitAgentCountRejectsAnUndersizedPlanAndUsesTheExactFallback() {
        Fixture fixture = fixture("""
                {
                  "root": {
                    "id": "root",
                    "members": [{
                      "id": "only-one",
                      "agent": {
                        "agentId": "evidence-researcher",
                        "label": "Research",
                        "instruction": "Find the evidence.",
                        "guideMessage": "I’m finding the evidence for you.",
                        "activeVerb": "Researching",
                        "completedVerb": "Researched",
                        "toolAccess": "READ_ONLY"
                      },
                      "workflow": null
                    }],
                    "edges": []
                  },
                  "guideMessage": "I’m delegating the three checks.",
                  "synthesisGuideMessage": "I’m combining the three findings."
                }
                """, 3, "Spawn exactly 3 sub-agents in parallel.");

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.runner.run(fixture.planner.callId(), fixture.context)).workflow();

        assertThat(plan.root().members()).hasSize(3);
        assertThat(plan.root().edges()).isEmpty();
        org.mockito.ArgumentCaptor<AgentInvocation> invocation =
                org.mockito.ArgumentCaptor.forClass(AgentInvocation.class);
        verify(fixture.execution).execute(invocation.capture());
        assertThat(invocation.getValue().request().content())
                .contains("\"userRequest\":\"Spawn exactly 3 sub-agents in parallel.\"")
                .contains("\"requiredAgentCount\":3");
        verify(fixture.recorder).lifecycle(
                org.mockito.ArgumentMatchers.eq("workflow_plan_fallback"), any(), any());
    }

    @Test
    void nestedPlanningUsesTheOwningAgentsAssignmentAndItsLocalAgentCount() {
        Fixture fixture = fixture("""
                {
                  "root": {
                    "id": "nested-checks",
                    "members": [
                      {
                        "id": "first",
                        "agent": {
                          "agentId": "evidence-researcher",
                          "label": "First check",
                          "instruction": "Check the first source.",
                          "guideMessage": "I’m checking the first source.",
                          "activeVerb": "Checking",
                          "completedVerb": "Checked",
                          "toolAccess": "READ_ONLY"
                        },
                        "workflow": null
                      },
                      {
                        "id": "second",
                        "agent": {
                          "agentId": "critical-reviewer",
                          "label": "Second check",
                          "instruction": "Check the second source.",
                          "guideMessage": "I’m checking the second source.",
                          "activeVerb": "Checking",
                          "completedVerb": "Checked",
                          "toolAccess": "READ_ONLY"
                        },
                        "workflow": null
                      }
                    ],
                    "edges": []
                  },
                  "guideMessage": "I’m running two nested checks.",
                  "synthesisGuideMessage": "I’m combining the nested checks."
                }
                """, 3, "Spawn exactly 3 top-level sub-agents in parallel.");
        AiWorkflowPlan.AgentTask assignment = new AiWorkflowPlan.AgentTask(
                "evidence-researcher", "Nested checks",
                "Spawn exactly 2 sub-agents in parallel to verify this assignment.",
                "I’m delegating the nested checks.", "Delegating", "Verified",
                AiWorkflowPlan.ToolAccess.READ_ONLY, AiWorkflowPlan.Delegation.FAN_OUT);
        AiWorkflowPlan outer = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("outer", List.of(
                        new AiWorkflowPlan.Member("owner", assignment, null))), null, null);
        AgentWorkflowContext nested = fixture.context
                .inWorkflow(outer, new AgentWorkflowContext.Location(
                        "outer", "main:1:outer", "main", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(outer, "owner", assignment, List.of());

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.runner.run(fixture.planner.callId(), nested)).workflow();

        assertThat(plan.root().members()).hasSize(2);
        org.mockito.ArgumentCaptor<AgentInvocation> invocation =
                org.mockito.ArgumentCaptor.forClass(AgentInvocation.class);
        verify(fixture.execution).execute(invocation.capture());
        assertThat(invocation.getValue().request().content())
                .contains("\"userRequest\":\"Spawn exactly 2 sub-agents in parallel to verify this assignment.\"")
                .contains("\"requiredAgentCount\":2");
    }

    @Test
    void nestedPlanningClampsItsRequestedCountToTheRootAgentLimit() {
        Fixture fixture = fixture("unused", 2, "Inspect it");
        AiWorkflowPlan.AgentTask assignment = new AiWorkflowPlan.AgentTask(
                "evidence-researcher", "Nested checks",
                "Spawn exactly 3 sub-agents in parallel.",
                "I’m delegating nested checks.", "Delegating", "Verified",
                AiWorkflowPlan.ToolAccess.READ_ONLY, AiWorkflowPlan.Delegation.FAN_OUT);
        AiWorkflowPlan outer = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("outer", List.of(
                        new AiWorkflowPlan.Member("owner", assignment, null))), null, null);
        AgentWorkflowContext nested = fixture.context
                .inWorkflow(outer, new AgentWorkflowContext.Location(
                        "outer", "main:1:outer", "main", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(outer, "owner", assignment, List.of());

        AiWorkflowPlan plan = ((AgentDecision.Delegate)
                fixture.runner.run(fixture.planner.callId(), nested)).workflow();

        assertThat(plan.root().members()).hasSize(2);
        org.mockito.ArgumentCaptor<AgentInvocation> invocation =
                org.mockito.ArgumentCaptor.forClass(AgentInvocation.class);
        verify(fixture.execution).execute(invocation.capture());
        assertThat(invocation.getValue().request().content())
                .contains("\"requiredAgentCount\":2");
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
        return fixture(modelOutput, maximumAgents, "Investigate it");
    }

    private Fixture fixture(String modelOutput, int maximumAgents, String prompt) {
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
                "request-1", "conversation-1", "user-1", "model", prompt,
                false, false, maximumAgents, "verification", "agents", true,
                AiWorkflowIntent.explicitlyRequestsAgents(prompt), false);
        ChatRequest chatRequest = new ChatRequest(prompt, "request-1", null,
                "conversation-1", null, List.of(), null, "model", "verification", "agents");
        AgentWorkflowContext context = AgentWorkflowContext.root(
                ChatExecutionContext.fromRequest(chatRequest, List.of(),
                        new org.springframework.ai.chat.messages.UserMessage(prompt),
                        null, recorder, false, false), request, 3);
        return new Fixture(planner, runner, context, recorder, execution);
    }

    private record Fixture(PlannerAgent planner, AgentRunner runner,
                           AgentWorkflowContext context, AiTrajectoryRecorder recorder,
                           AgentExecutionService execution) { }
}

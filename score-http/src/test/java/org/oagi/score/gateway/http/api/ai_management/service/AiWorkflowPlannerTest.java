package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntime;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntimeRegistry;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWorkflowPlannerTest {

    @Test
    void letsTheModelChooseParallelExecutionFromThePromptWithoutClientSettings() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                runtimes, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {
                  "workflow":"parallel",
                  "toolRequired":true,
                  "guideMessage":"I’ll review both BODs independently.",
                  "activeVerb":"Reviewing",
                  "completedVerb":"Reviewed",
                  "synthesisGuideMessage":"I’ll compare the two results.",
                  "synthesisActiveVerb":"Comparing",
                  "synthesisCompletedVerb":"Compared",
                  "tasks":[
                    {"label":"Sync Purchase Order","agentId":"evidence-researcher",
                     "instruction":"Read Sync Purchase Order in 10.13.",
                     "guideMessage":"I’ll review Sync Purchase Order.",
                     "activeVerb":"Reviewing","completedVerb":"Reviewed"},
                    {"label":"Get Purchase Order","agentId":"evidence-researcher",
                     "instruction":"Read Get Purchase Order in 10.13.",
                     "guideMessage":"I’ll review Get Purchase Order.",
                     "activeVerb":"Reviewing","completedVerb":"Reviewed"}
                  ]
                }
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Compare the current data for these two BODs.", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "default",
                Map.of(), "ask");
        AiRuntime.Context context = new AiRuntime.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root);

        AiWorkflowPlan plan = planner.plan(context);

        assertThat(plan.workflow()).isEqualTo("parallel");
        assertThat(plan.activeVerb()).isEqualTo("Reviewing");
        assertThat(plan.synthesisCompletedVerb()).isEqualTo("Compared");
        assertThat(plan.tasks()).hasSize(2)
                .extracting(AiWorkflowPlan.Task::agentId)
                .containsExactly("evidence-researcher", "evidence-researcher");
        ArgumentCaptor<AiRuntime.Context> planning = ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes).execute(eq("default"), planning.capture());
        assertThat(planning.getValue().toolsEnabled()).isFalse();
        assertThat(planning.getValue().toolPolicy()).isEqualTo(AiRuntime.ToolPolicy.NONE);
        assertThat(planning.getValue().streamVisibleContent()).isFalse();
        assertThat(request.multiAgent()).isEqualTo(AiMultiAgentOptions.single());
    }

    @Test
    void expandsAModelSelectedParallelWorkflowToTwoIndependentTasks() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                runtimes, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {
                  "workflow":"parallel",
                  "toolRequired":true,
                  "guideMessage":"I’ll inspect the current records independently.",
                  "activeVerb":"Inspecting",
                  "completedVerb":"Inspected",
                  "synthesisGuideMessage":"I’ll reconcile the independent results.",
                  "synthesisActiveVerb":"Reconciling",
                  "synthesisCompletedVerb":"Reconciled",
                  "tasks":[
                    {"label":"Primary inspection","agentId":"evidence-researcher",
                     "instruction":"Inspect the primary record.",
                     "guideMessage":"Inspecting the primary record.",
                     "activeVerb":"Inspecting","completedVerb":"Inspected"}
                  ]
                }
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Compare the current records independently.", "request-1", null,
                "conversation-1", null, List.of(), null, "model", "high", "default",
                Map.of(), "ask");

        AiWorkflowPlan plan = planner.plan(new AiRuntime.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.workflow()).isEqualTo("parallel");
        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks()).extracting(AiWorkflowPlan.Task::label)
                .containsExactly("Primary inspection", "Independent verification");
    }

    @Test
    void sendsRecentConversationToThePlannerAndFailsClosedForActionFollowUps() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                runtimes, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {
                  "workflow":"direct",
                  "toolRequired":false,
                  "guideMessage":null,
                  "activeVerb":"Answering",
                  "completedVerb":"Answered",
                  "synthesisGuideMessage":null,
                  "synthesisActiveVerb":"Answering",
                  "synthesisCompletedVerb":"Answered",
                  "tasks":[]
                }
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "add additional schemes", "request-2", null,
                "conversation-1", null, List.of(), null, "model", "medium", "default",
                Map.of(), "auto");
        AiRuntime.Context context = new AiRuntime.Context(request, List.of(
                new UserMessage("create a sample business context"),
                new AssistantMessage("Created business context 73. I can add values next.")),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root);

        AiWorkflowPlan plan = planner.plan(context);

        assertThat(plan.toolsNeeded()).isTrue();
        assertThat(plan.workflow()).isEqualTo("direct");
        assertThat(plan.guideMessage()).isNotBlank();
        ArgumentCaptor<AiRuntime.Context> planning = ArgumentCaptor.forClass(AiRuntime.Context.class);
        verify(runtimes).execute(eq("default"), planning.capture());
        assertThat(planning.getValue().userMessage().getText())
                .isEqualTo("Return the workflow plan for the untrusted input above.");
        assertThat(((SystemMessage) planning.getValue().history().getFirst()).getText())
                .contains("Recent conversation:", "create a sample business context",
                        "Created business context 73", "add additional schemes",
                        "Explicit agent workflow requested: false",
                        "Explicit fan-out requested: false", "Maximum workers: 3",
                        "Strategy preference: \"balanced\"", "Active workflow: null",
                        "evidence-researcher")
                .doesNotContain("${");
    }

    @Test
    void keepsAConversationalFollowUpToolFreeWhenThePlannerSaysNoTools() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                runtimes, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {"workflow":"direct","toolRequired":false,"guideMessage":null,
                 "activeVerb":"Answering","completedVerb":"Answered",
                 "synthesisGuideMessage":null,"synthesisActiveVerb":"Answering",
                 "synthesisCompletedVerb":"Answered","tasks":[]}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "thanks", "request-2", null, "conversation-1", null, List.of(), null,
                "model", "medium", "default", Map.of(), "ask");

        AiWorkflowPlan plan = planner.plan(new AiRuntime.Context(request,
                List.of(new AssistantMessage("The lookup is complete.")),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.toolsNeeded()).isFalse();
        assertThat(plan.guideMessage()).isNull();
    }

    @Test
    void preservesAgentWorkflowForAMutationFollowUpWhenTheModelReturnsNoTasks() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                runtimes, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {"workflow":"chain","toolRequired":true,
                 "guideMessage":"I'll prepare the update.",
                 "activeVerb":"Preparing update","completedVerb":"Prepared update",
                 "synthesisGuideMessage":null,"synthesisActiveVerb":"Applying update",
                 "synthesisCompletedVerb":"Applied update","tasks":[]}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = AiMultiAgentIntent.applyExplicitDelegation(new ChatRequest(
                "add values using sub-agents", "request-2", null, "conversation-1", null,
                List.of(), null, "model", "medium", "default", Map.of(), "auto"));

        AiWorkflowPlan plan = planner.plan(new AiRuntime.Context(request,
                List.of(new AssistantMessage("Created business context 75.")),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.workflow()).isEqualTo("orchestrator_workers");
        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.label()).isEqualTo("Current-state evidence");
            assertThat(task.instruction()).contains("exact target", "do not mutate");
        });
    }


    @Test
    void enforcesPersistedOrchestratorWorkflowForAnOrdinaryFollowUp() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                runtimes, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {"workflow":"direct","toolRequired":true,
                 "guideMessage":"I'll inspect the record.",
                 "activeVerb":"Inspecting","completedVerb":"Inspected",
                 "synthesisGuideMessage":null,"synthesisActiveVerb":"Synthesizing",
                 "synthesisCompletedVerb":"Synthesized","tasks":[]}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Show business context 75", "request-3", null, "conversation-1", null,
                List.of(), null, "model", "medium", "default", Map.of(), "ask",
                AiMultiAgentOptions.single(), "orchestrator_workers");

        AiWorkflowPlan plan = planner.plan(new AiRuntime.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.workflow()).isEqualTo("orchestrator_workers");
        assertThat(plan.tasks()).hasSize(1);
    }

    @Test
    void enforcesPersistedDirectWorkflowWithoutDisablingRequiredTools() {
        AiRuntimeRegistry runtimes = mock(AiRuntimeRegistry.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                runtimes, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(runtimes.execute(eq("default"), any())).thenReturn(new AiRuntime.Result("""
                {"workflow":"parallel","toolRequired":true,
                 "guideMessage":"I'll inspect the record.",
                 "activeVerb":"Inspecting","completedVerb":"Inspected",
                 "synthesisGuideMessage":null,"synthesisActiveVerb":"Synthesizing",
                 "synthesisCompletedVerb":"Synthesized","tasks":[
                   {"label":"Record","agentId":"evidence-researcher",
                    "instruction":"Inspect the record.","guideMessage":"Inspecting.",
                    "activeVerb":"Inspecting","completedVerb":"Inspected"}]}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Show business context 75", "request-4", null, "conversation-1", null,
                List.of(), null, "model", "medium", "default", Map.of(), "ask",
                AiMultiAgentOptions.single(), "direct");

        AiWorkflowPlan plan = planner.plan(new AiRuntime.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.workflow()).isEqualTo("direct");
        assertThat(plan.toolsNeeded()).isTrue();
        assertThat(plan.tasks()).isEmpty();
    }
}

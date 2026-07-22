package org.oagi.score.gateway.http.api.ai_management.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
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
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
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
                "conversation-1", null, List.of(), null, "model", "high", "ask");
        AiChatExecutor.Context context = new AiChatExecutor.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root);

        AiWorkflowPlan plan = planner.plan(context);

        assertThat(plan.workflow()).isEqualTo("parallel");
        assertThat(plan.activeVerb()).isEqualTo("Reviewing");
        assertThat(plan.synthesisCompletedVerb()).isEqualTo("Compared");
        assertThat(plan.tasks()).hasSize(2)
                .extracting(AiWorkflowPlan.Task::agentId)
                .containsExactly("evidence-researcher", "evidence-researcher");
        ArgumentCaptor<AiChatExecutor.Context> planning = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor).execute(planning.capture());
        assertThat(planning.getValue().toolsEnabled()).isFalse();
        assertThat(planning.getValue().toolPolicy()).isEqualTo(AiChatExecutor.ToolPolicy.NONE);
        assertThat(planning.getValue().streamVisibleContent()).isFalse();
        assertThat(request.multiAgent()).isEqualTo(AiMultiAgentOptions.single());
    }

    @Test
    void expandsAModelSelectedParallelWorkflowToTwoIndependentTasks() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
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
                "conversation-1", null, List.of(), null, "model", "high", "ask");

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.workflow()).isEqualTo("parallel");
        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks()).extracting(AiWorkflowPlan.Task::label)
                .containsExactly("Primary inspection", "#2");
    }

    @Test
    void sendsRecentConversationToThePlannerAndFailsClosedForActionFollowUps() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
                {
                  "workflow":"direct",
                  "toolRequired":false,
                  "guideMessage":"I’ll continue the requested work.",
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
                "conversation-1", null, List.of(), null, "model", "medium", "auto");
        AiChatExecutor.Context context = new AiChatExecutor.Context(request, List.of(
                new UserMessage("create a sample business context"),
                new AssistantMessage("Created business context 73. I can add values next.")),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root);

        AiWorkflowPlan plan = planner.plan(context);

        assertThat(plan.toolsNeeded()).isTrue();
        assertThat(plan.workflow()).isEqualTo("direct");
        assertThat(plan.guideMessage()).isEqualTo("I’ll continue the requested work.");
        ArgumentCaptor<AiChatExecutor.Context> planning = ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor).execute(planning.capture());
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
    void instructsEveryGuideAndVerbToUseTheCurrentUserRequestLanguage() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, new AiAgentCatalog(new DefaultResourceLoader()),
                new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
                {"workflow":"direct","toolRequired":false,"guideMessage":null,
                 "activeVerb":"Answering","completedVerb":"Answered",
                 "synthesisGuideMessage":null,"synthesisActiveVerb":"Answering",
                 "synthesisCompletedVerb":"Answered","tasks":[]}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Please summarize the current explanation.", "request-language", null,
                "conversation-language", null, List.of(), null, "model", "medium", "ask");

        planner.plan(new AiChatExecutor.Context(request, List.of(
                new UserMessage("Ajoutez les valeurs manquantes."),
                new AssistantMessage("Je vais vérifier les valeurs actuelles.")),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        ArgumentCaptor<AiChatExecutor.Context> planning =
                ArgumentCaptor.forClass(AiChatExecutor.Context.class);
        verify(executor).execute(planning.capture());
        assertThat(((SystemMessage) planning.getValue().history().getFirst()).getText())
                .contains("User request: \"Please summarize the current explanation.\"",
                        "language used by the current User request",
                        "Never copy the language of Recent conversation",
                        "every root, child, route, task, and synthesis guide/verb")
                .doesNotContain("in the response language");
    }

    @Test
    void plannerFailureUsesLanguageNeutralPresentationInsteadOfEnglishFallbackText() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, new AiAgentCatalog(new DefaultResourceLoader()),
                new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenThrow(new IllegalStateException("planner unavailable"));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "현재 값을 확인해줘.", "request-fallback-language", null,
                "conversation-language", null, List.of(), null, "model", "medium", "ask");

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.guideMessage()).isNull();
        assertThat(plan.synthesisGuideMessage()).isNull();
        assertThat(plan.activeVerb()).isEqualTo("…");
        assertThat(plan.completedVerb()).isEqualTo("✓");
        assertThat(List.of(plan.activeVerb(), plan.completedVerb(),
                plan.synthesisActiveVerb(), plan.synthesisCompletedVerb()))
                .doesNotContain("Working", "Completed", "Synthesizing", "Synthesized");
    }

    @Test
    void keepsAConversationalFollowUpToolFreeWhenThePlannerSaysNoTools() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
                {"workflow":"direct","toolRequired":false,"guideMessage":null,
                 "activeVerb":"Answering","completedVerb":"Answered",
                 "synthesisGuideMessage":null,"synthesisActiveVerb":"Answering",
                 "synthesisCompletedVerb":"Answered","tasks":[]}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "thanks", "request-2", null, "conversation-1", null, List.of(), null,
                "model", "medium", "ask");

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request,
                List.of(new AssistantMessage("The lookup is complete.")),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.toolsNeeded()).isFalse();
        assertThat(plan.guideMessage()).isNull();
    }

    @Test
    void preservesAgentWorkflowForAMutationFollowUpWhenTheModelReturnsNoTasks() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
                {"workflow":"chain","toolRequired":true,
                 "guideMessage":"I'll prepare the update.",
                 "activeVerb":"Preparing update","completedVerb":"Prepared update",
                 "synthesisGuideMessage":null,"synthesisActiveVerb":"Applying update",
                 "synthesisCompletedVerb":"Applied update","tasks":[]}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = AiWorkflowIntent.applyExplicitDelegation(new ChatRequest(
                "add values using sub-agents", "request-2", null, "conversation-1", null,
                List.of(), null, "model", "medium", "auto"));

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request,
                List.of(new AssistantMessage("Created business context 75.")),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.workflow()).isEqualTo("orchestrator_workers");
        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.label()).isEqualTo("#1");
            assertThat(task.instruction()).contains("exact target", "do not mutate");
        });
    }


    @Test
    void enforcesPersistedOrchestratorWorkflowForAnOrdinaryFollowUp() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
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
                List.of(), null, "model", "medium", "ask",
                AiMultiAgentOptions.single(), "orchestrator_workers", null);

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.workflow()).isEqualTo("orchestrator_workers");
        assertThat(plan.tasks()).hasSize(1);
    }

    @Test
    void enforcesPersistedDirectWorkflowWithoutDisablingRequiredTools() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
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
                List.of(), null, "model", "medium", "ask",
                AiMultiAgentOptions.single(), "direct", null);

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.workflow()).isEqualTo("direct");
        assertThat(plan.toolsNeeded()).isTrue();
        assertThat(plan.tasks()).isEmpty();
    }

    @Test
    void collapsesAnAutomaticSingleEvidenceWorkerFollowedByTheLead() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
                {"root":{"id":"root","workflow":"chain","toolRequired":true,
                  "guideMessage":"I’ll verify the records and create them only if missing.",
                  "activeVerb":"Handling context records","completedVerb":"Handled context records",
                  "synthesisGuideMessage":null,"synthesisActiveVerb":"Completing",
                  "synthesisCompletedVerb":"Completed","task":null,"selectedRoute":null,
                  "children":[
                    {"id":"research","workflow":"direct","toolRequired":true,
                     "guideMessage":"I’ll inspect the current context records.",
                     "activeVerb":"Inspecting","completedVerb":"Inspected",
                     "synthesisGuideMessage":null,"synthesisActiveVerb":"Inspecting",
                     "synthesisCompletedVerb":"Inspected",
                     "task":{"label":"Context evidence","agentId":"evidence-researcher",
                       "instruction":"Read the exact context scheme and category.",
                       "guideMessage":"Inspecting current records.",
                       "activeVerb":"Inspecting","completedVerb":"Inspected"},
                     "selectedRoute":null,"children":[],"routes":{}},
                    {"id":"lead","workflow":"direct","toolRequired":true,
                     "guideMessage":"I’ll create only records that are missing.",
                     "activeVerb":"Completing","completedVerb":"Completed",
                     "synthesisGuideMessage":null,"synthesisActiveVerb":"Completing",
                     "synthesisCompletedVerb":"Completed","task":null,
                     "selectedRoute":null,"children":[],"routes":{}}
                  ],"routes":{}}}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Create the context scheme and category if they are missing.",
                "request-4", null, "conversation-1", null, List.of(), null,
                "model", "medium", "ask");
        AiChatExecutor.Context context = new AiChatExecutor.Context(request,
                List.of(new AssistantMessage("The prior result referenced scheme ID 34 and category ID 44.")),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root);

        AiWorkflowPlan plan = planner.plan(context);

        assertThat(plan.workflow()).isEqualTo("direct");
        assertThat(plan.toolsNeeded()).isTrue();
        assertThat(plan.root()).isNotNull();
        assertThat(plan.root().task()).isNull();
        assertThat(plan.root().children()).isEmpty();
        assertThat(plan.guideMessage()).isEqualTo("I’ll inspect the current context records.")
                .doesNotContain("create");
    }

    @Test
    void normalizesARecursiveComposedWorkflowPlan() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiAgentCatalog catalog = new AiAgentCatalog(new DefaultResourceLoader());
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, catalog, new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
                {"root":{"id":"root","workflow":"chain","toolRequired":true,
                  "guideMessage":"I’ll gather and verify the current evidence.",
                  "activeVerb":"Investigating","completedVerb":"Investigated",
                  "synthesisGuideMessage":null,"synthesisActiveVerb":"Synthesizing",
                  "synthesisCompletedVerb":"Synthesized","task":null,"selectedRoute":null,
                  "children":[
                    {"id":"research","workflow":"direct","toolRequired":true,
                     "guideMessage":"I’ll research the record.","activeVerb":"Researching",
                     "completedVerb":"Researched","synthesisGuideMessage":null,
                     "synthesisActiveVerb":"Synthesizing","synthesisCompletedVerb":"Synthesized",
                     "task":{"label":"Evidence","agentId":"evidence-researcher",
                       "instruction":"Read the exact current record and return evidence only.",
                       "guideMessage":"Researching the current record.",
                       "activeVerb":"Researching","completedVerb":"Researched",
                       "toolAccess":"READ_ONLY"},
                     "selectedRoute":null,"children":[],"routes":{}},
                    {"id":"answer","workflow":"direct","toolRequired":true,
                     "guideMessage":null,"activeVerb":"Answering","completedVerb":"Answered",
                     "synthesisGuideMessage":null,"synthesisActiveVerb":"Answering",
                     "synthesisCompletedVerb":"Answered","task":null,"selectedRoute":null,
                     "children":[],"routes":{}}
                  ],"routes":{}}}
                """));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Investigate this current record with agents.", "request-5", null,
                "conversation-1", null, List.of(), null, "model", "medium", "ask",
                new AiMultiAgentOptions(true, 3, "balanced"), null, null);

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.root()).isNotNull();
        assertThat(plan.workflow()).isEqualTo("chain");
        assertThat(plan.root().children()).hasSize(2);
        assertThat(plan.root().children().getFirst().task().agentId())
                .isEqualTo("evidence-researcher");
        assertThat(plan.root().children().getFirst().task().toolAccess())
                .isEqualTo(AiWorkflowPlan.ToolAccess.READ_ONLY);
    }

    @Test
    void countsPlainParallelBranchesAgainstTheWorkerLimit() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, new AiAgentCatalog(new DefaultResourceLoader()),
                new ObjectMapper(), new DefaultResourceLoader());
        // Four plain (non-worker) branches each consume a concurrent model
        // execution, so a three-agent limit must reject the graph and fall back.
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
                {"root":{"id":"root","workflow":"parallel","toolRequired":true,
                  "guideMessage":null,"activeVerb":"Working","completedVerb":"Completed",
                  "synthesisGuideMessage":null,"synthesisActiveVerb":"Synthesizing",
                  "synthesisCompletedVerb":"Synthesized","task":null,"selectedRoute":null,
                  "children":[%s],"routes":{}}}
                """.formatted(String.join(",",
                plainLeafJson("a"), plainLeafJson("b"), plainLeafJson("c"), plainLeafJson("d")))));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Investigate this current record.", "request-6", null,
                "conversation-1", null, List.of(), null, "model", "medium", "ask",
                new AiMultiAgentOptions(true, 3, "balanced"), null, null);

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.root()).isNull();
        assertThat(plan.workflow()).isEqualTo("direct");
        assertThat(plan.toolsNeeded()).isTrue();
    }

    @Test
    void acceptsPlainParallelBranchesWithinTheWorkerLimit() {
        AiChatExecutor executor = mock(AiChatExecutor.class);
        AiWorkflowPlanner planner = new AiWorkflowPlanner(
                executor, new AiAgentCatalog(new DefaultResourceLoader()),
                new ObjectMapper(), new DefaultResourceLoader());
        when(executor.execute(any())).thenReturn(new AiChatExecutor.Result("""
                {"root":{"id":"root","workflow":"parallel","toolRequired":true,
                  "guideMessage":null,"activeVerb":"Working","completedVerb":"Completed",
                  "synthesisGuideMessage":null,"synthesisActiveVerb":"Synthesizing",
                  "synthesisCompletedVerb":"Synthesized","task":null,"selectedRoute":null,
                  "children":[%s],"routes":{}}}
                """.formatted(String.join(",",
                plainLeafJson("a"), plainLeafJson("b"), plainLeafJson("c")))));
        AiTrajectoryRecorder root = mock(AiTrajectoryRecorder.class);
        when(root.fork(any())).thenReturn(mock(AiTrajectoryRecorder.class));
        ChatRequest request = new ChatRequest(
                "Investigate this current record.", "request-7", null,
                "conversation-1", null, List.of(), null, "model", "medium", "ask",
                new AiMultiAgentOptions(true, 3, "balanced"), null, null);

        AiWorkflowPlan plan = planner.plan(new AiChatExecutor.Context(request, List.of(),
                new UserMessage(request.prompt()), mock(ScoreUser.class), root));

        assertThat(plan.root()).isNotNull();
        assertThat(plan.workflow()).isEqualTo("parallel");
        assertThat(plan.root().children()).hasSize(3);
    }

    private String plainLeafJson(String id) {
        return """
                {"id":"%s","workflow":"direct","toolRequired":true,"guideMessage":null,
                 "activeVerb":"Working","completedVerb":"Completed","synthesisGuideMessage":null,
                 "synthesisActiveVerb":"Synthesizing","synthesisCompletedVerb":"Synthesized",
                 "task":null,"selectedRoute":null,"children":[],"routes":{}}
                """.formatted(id);
    }
}

package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatSession;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrails;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutputRetryHandoffException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.support.TestAgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionState;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway.RequestFence;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowType;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddleware;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareChain;
import org.oagi.score.gateway.http.api.ai_management.middleware.MiddlewareState;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AgentRunnerTest {

    @Test
    void sharesOneMiddlewareStateWithTheChatTransport() {
        MiddlewareState.Key<String> key = new MiddlewareState.Key<>(
                "state-writer", "correlation", String.class);
        AiMiddleware writer = new AiMiddleware() {
            @Override public String id() { return "state-writer"; }
            @Override public AgentResult beforeAgent(AgentContext context) {
                context.state().put(key, "shared");
                return AgentResult.continueWith(context);
            }
        };
        ScoreAiProperties.Middleware settings = new ScoreAiProperties.Middleware();
        settings.setProfiles(Map.of("default", List.of("state-writer")));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(writer));
        AtomicReference<AgentChatSession> captured = new AtomicReference<>();
        AgentExecutionService chat = TestAgentExecutionService.chat(session -> {
            captured.set(session);
            return new AgentChatResult("answer");
        });
        Agent agent = chatAgent("state-agent", "instruction");

        new AgentRunner(chat, null, null, null, List.of(agent), chain)
                .run(agent, context("state-agent"));

        assertThat(captured.get().middlewareState().get(key)).contains("shared");
    }

    @Test
    void executesAgentAndModelMiddlewareOnTheSharedRunnerPath() {
        List<String> order = new java.util.ArrayList<>();
        AiMiddleware middleware = new AiMiddleware() {
            @Override public String id() { return "runner-trace"; }
            @Override public AgentResult beforeAgent(AgentContext context) {
                order.add("before-agent"); return AgentResult.continueWith(context);
            }
            @Override public ModelResult beforeModel(ModelContext context) {
                order.add("before-model"); return ModelResult.continueWith(context);
            }
            @Override public AgentRunResult wrapModelCall(ModelContext context, ModelCall next) {
                order.add("wrap-model-before");
                AgentRunResult result = next.call(context);
                order.add("wrap-model-after");
                return result;
            }
            @Override public ModelResult afterModel(ModelContext context, AgentRunResult response) {
                order.add("after-model"); return ModelResult.completeWith(response);
            }
            @Override public AgentResult afterAgent(AgentContext context, AgentDecision response) {
                order.add("after-agent"); return AgentResult.completeWith(response);
            }
        };
        ScoreAiProperties.Middleware settings = new ScoreAiProperties.Middleware();
        settings.setProfiles(Map.of("default", List.of("runner-trace")));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(middleware));
        AgentExecutionService execution = invocation -> result("answer", invocation.session().agent());
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(model());
        Agent agent = agent(org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                AgentGuardrails.none(), "middleware-agent");

        AgentDecision decision = new AgentRunner(execution, models, null, null,
                List.of(agent), chain).run(agent, context("middleware-agent"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(order).containsExactly("before-agent", "before-model",
                "wrap-model-before", "wrap-model-after", "after-model", "after-agent");
    }

    @Test
    void checksAnAfterAgentReplacementBeforeReturningIt() {
        AiMiddleware replacing = new AiMiddleware() {
            @Override public String id() { return "replace-output"; }
            @Override
            public AgentResult afterAgent(AgentContext context, AgentDecision response) {
                return AgentResult.completeWith(new AgentDecision.Complete(
                        new AgentOutput("secret=replaced")));
            }
        };
        ScoreAiProperties.Middleware settings = new ScoreAiProperties.Middleware();
        settings.setProfiles(Map.of("default", List.of("replace-output")));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(replacing));
        AgentOutputGuardrail redact = request -> new AgentOutputGuardrail.Result.Rewrite(
                new AiMessage.Assistant("[checked]"),
                GuardrailDecision.of("final-check", "1", GuardrailDecision.Action.REWRITE));
        Agent agent = agent(org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                new AgentGuardrails(List.of(), List.of(redact)), "replacement-agent");
        AgentExecutionService execution = invocation -> result("initial", invocation.session().agent());
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(model());

        AgentDecision.Complete decision = (AgentDecision.Complete) new AgentRunner(
                execution, models, null, null, List.of(agent), chain)
                .run(agent, context("replacement-agent"));

        assertThat(decision.result().content()).isEqualTo("[checked]");
        assertThat(decision.result().passedOutputGuardrail(
                org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail.Scope.INTERNAL))
                .isTrue();
    }

    @Test
    void assignedAgentLifecyclePublishesThePostMiddlewareResult() {
        AiMiddleware replacing = new AiMiddleware() {
            @Override public String id() { return "replace-assigned-output"; }
            @Override
            public AgentResult afterAgent(AgentContext context, AgentDecision response) {
                return AgentResult.completeWith(new AgentDecision.Complete(
                        new AgentOutput("final middleware result")));
            }
        };
        ScoreAiProperties.Middleware settings = new ScoreAiProperties.Middleware();
        settings.setProfiles(Map.of("default", List.of("replace-assigned-output")));
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                new ChatRequest("prompt", "request", "assigned-agent", "conversation",
                        null, List.of(), null, "model", null, null),
                List.of(), new AiMessage.User("prompt"), null, recorder,
                false, false, AgentToolPolicy.NONE, 0);
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "assigned-agent", "Assigned", "Complete the assignment.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("assigned-workflow", List.of(
                        new AiWorkflowPlan.Member("member", task, null))), null, null);
        AgentWorkflowContext workflow = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request", "conversation", "user",
                                "model", "prompt", false, false, 1, "balanced",
                                null, true, false, false), 1)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "assigned-workflow", "root:assigned", "root", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(plan, "member", task, List.of());
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("assigned-agent"), "Assigned Agent", "Completes an assignment",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Skip(
                        new AgentDecision.Complete(new AgentOutput("initial result"))),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                AgentGuardrails.none(), true));

        AgentDecision.Complete decision = (AgentDecision.Complete) new AgentRunner(
                null, null, null, null, List.of(agent),
                new AiMiddlewareChain(settings, List.of(replacing))).run(agent, workflow);

        assertThat(decision.result().content()).isEqualTo("final middleware result");
        verify(recorder).lifecycle(
                org.mockito.ArgumentMatchers.eq("subagent_completed"), any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(metadata ->
                        "final middleware result".equals(metadata.get("result"))));
    }

    @Test
    void assignedAgentLifecycleFailsWhenPostMiddlewareThrows() {
        AiMiddleware failing = new AiMiddleware() {
            @Override public String id() { return "fail-assigned-output"; }
            @Override
            public AgentResult afterAgent(AgentContext context, AgentDecision response) {
                throw new IllegalStateException("after-agent failed");
            }
        };
        ScoreAiProperties.Middleware settings = new ScoreAiProperties.Middleware();
        settings.setProfiles(Map.of("default", List.of("fail-assigned-output")));
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                new ChatRequest("prompt", "request", "assigned-agent", "conversation",
                        null, List.of(), null, "model", null, null),
                List.of(), new AiMessage.User("prompt"), null, recorder,
                false, false, AgentToolPolicy.NONE, 0);
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "assigned-agent", "Assigned", "Complete the assignment.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("assigned-workflow", List.of(
                        new AiWorkflowPlan.Member("member", task, null))), null, null);
        AgentWorkflowContext workflow = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request", "conversation", "user",
                                "model", "prompt", false, false, 1, "balanced",
                                null, true, false, false), 1)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "assigned-workflow", "root:assigned", "root", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(plan, "member", task, List.of());
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("assigned-agent"), "Assigned Agent", "Completes an assignment",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Skip(
                        new AgentDecision.Complete(new AgentOutput("initial result"))),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                AgentGuardrails.none(), true));

        assertThatThrownBy(() -> new AgentRunner(
                null, null, null, null, List.of(agent),
                new AiMiddlewareChain(settings, List.of(failing))).run(agent, workflow))
                .isInstanceOf(org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareException.class)
                .hasMessageContaining("fail-assigned-output");
        verify(recorder).lifecycle(
                org.mockito.ArgumentMatchers.eq("subagent_failed"), any(),
                org.mockito.ArgumentMatchers.anyMap());

        org.mockito.Mockito.reset(recorder);
        AiMiddleware beforeFailing = new AiMiddleware() {
            @Override public String id() { return "fail-before-assigned"; }
            @Override
            public AgentResult beforeAgent(AgentContext context) {
                throw new IllegalStateException("before-agent failed");
            }
        };
        ScoreAiProperties.Middleware beforeSettings = new ScoreAiProperties.Middleware();
        beforeSettings.setProfiles(Map.of("default", List.of("fail-before-assigned")));

        assertThatThrownBy(() -> new AgentRunner(
                null, null, null, null, List.of(agent),
                new AiMiddlewareChain(beforeSettings, List.of(beforeFailing)))
                .run(agent, workflow))
                .isInstanceOf(org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareException.class)
                .hasMessageContaining("fail-before-assigned");
        verify(recorder).lifecycle(
                org.mockito.ArgumentMatchers.eq("subagent_failed"), any(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void modelOnlyExecutionServiceRemainsLambdaCompatible() {
        AgentExecutionService execution = invocation -> result(
                "lambda answer", invocation.session().agent());

        assertThatThrownBy(() -> execution.executeChat(mock(AgentChatSession.class)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void bindsDefinitionToolsAndFencesTheirGatewayForTheInvocation() {
        AtomicReference<org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation> captured =
                new AtomicReference<>();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> {
            captured.set(invocation);
            return result("answer", invocation.session().agent());
        });
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(model());
        AiTool tool = tool();
        ToolSet tools = new ToolSet(List.of(tool));
        ToolExecutionGateway gateway = new ToolExecutionGateway(tools,
                mock(ToolGuardrailRegistry.class), List.of(), RequestFence.ALLOW,
                ExecutionObserver.noop(), new ExecutionState(), 4096);
        Agent agent = agent((ignored, context) -> new AgentToolBinding(tools, gateway),
                AgentGuardrails.none(), "tool-agent");
        AgentRunner runner = new AgentRunner(execution, models, null, null, List.of(agent));

        AgentDecision decision = runner.run(agent, context("tool-agent"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(captured.get().session().tools().isEmpty()).isFalse();
        assertThat(captured.get().tools()).isNotSameAs(gateway);
        assertThat(captured.get().tools().enabled()).isTrue();
    }

    @Test
    void definitionOwnedToolChecksTheRunDeadlineAtSideEffectAdmission() {
        AtomicBoolean deadlineExceeded = new AtomicBoolean();
        AtomicBoolean toolInvoked = new AtomicBoolean();
        AiTool tool = new AiTool() {
            private final ToolSpecification specification = new ToolSpecification(
                    new ToolId("deadline-tool"), "deadline_tool", "Deadline tool",
                    "{}", "{}", ToolEffect.READ_ONLY);

            @Override
            public ToolSpecification specification() {
                return specification;
            }

            @Override
            public ToolResult execute(ToolArguments arguments,
                                      ToolExecutionContext context) {
                toolInvoked.set(true);
                return new ToolResult("{}");
            }
        };
        ToolGuardrailRegistry registry = mock(ToolGuardrailRegistry.class);
        when(registry.resolve(any(), any())).thenReturn(
                new ToolGuardrailRegistry.Set(List.of(), List.of()));
        ToolSet tools = new ToolSet(List.of(tool));
        ToolExecutionGateway gateway = new ToolExecutionGateway(tools, registry,
                List.of(), RequestFence.ALLOW, ExecutionObserver.noop(),
                new ExecutionState(), 4096);
        WorkflowRunControl runControl = new WorkflowRunControl() {
            @Override
            public void checkpoint() {
                if (deadlineExceeded.get()) {
                    throw new IllegalStateException("deadline exceeded");
                }
            }

            @Override
            public void recordUsage(AiUsageSnapshot usage) {
            }

            @Override
            public void registerUsage(java.util.function.Supplier<AiUsageSnapshot> usage,
                                      Runnable lateWriteFence) {
            }
        };
        Agent agent = agent((ignored, context) -> new AgentToolBinding(tools, gateway),
                AgentGuardrails.none(), "deadline-agent");
        AgentExecutionService execution = invocation -> {
            deadlineExceeded.set(true);
            assertThatThrownBy(() -> invocation.tools().execute(
                    tool.specification().id(), new AiTool.ToolArguments("{}"),
                    invocation.scope()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deadline");
            deadlineExceeded.set(false);
            return result("answer", invocation.session().agent());
        };
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(model());

        AgentDecision decision = new AgentRunner(execution, models, null, null,
                List.of(agent)).run(agent, context("deadline-agent", runControl));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(toolInvoked).isFalse();
    }

    @Test
    void retriesOutputPolicyThroughTheSamePreparedRequest() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger preparations = new AtomicInteger();
        List<String> inputs = new java.util.ArrayList<>();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> {
            inputs.add(invocation.request().content());
            return result(executions.incrementAndGet() == 1 ? "unsafe" : "safe",
                    invocation.session().agent());
        });
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(model());
        AgentOutputGuardrail output = request -> executions.get() == 1
                ? new AgentOutputGuardrail.Result.Retry("rewrite it",
                GuardrailDecision.of("output", "1", GuardrailDecision.Action.RETRY))
                : new AgentOutputGuardrail.Result.Allow(request.candidate(),
                GuardrailDecision.of("output", "1", GuardrailDecision.Action.ALLOW));
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("retry-agent"),
                "Retry Agent", "Retries unsafe output",
                new AgentDefinition.InstructionTemplate("instruction"),
                (agent, workflow) -> {
                    preparations.incrementAndGet();
                    return modelRequest();
                },
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(), List.of(output)), false);
        AgentRunner runner = new AgentRunner(execution, models, null, null,
                List.of(new DefinedAgent(definition)));

        AgentDecision decision = runner.run(new DefinedAgent(definition), context("retry-agent"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(((AgentDecision.Complete) decision).result().content()).isEqualTo("safe");
        assertThat(executions).hasValue(2);
        assertThat(preparations).hasValue(1);
        assertThat(inputs).hasSize(2);
        assertThat(inputs.getLast()).contains("OUTPUT_POLICY_FEEDBACK", "rewrite it");
    }

    @Test
    void appliesPublicOutputPolicyAfterTheResponseHandlerTransformsContent() {
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(model());
        AtomicReference<String> guardedCandidate = new AtomicReference<>();
        AgentOutputGuardrail redact = request -> {
            guardedCandidate.set(request.candidate().content());
            return new AgentOutputGuardrail.Result.Rewrite(
                    new AiMessage.Assistant("token=[REDACTED]"),
                    GuardrailDecision.of("output-redact", "1",
                            GuardrailDecision.Action.REWRITE));
        };
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("transform-agent"),
                "Transform Agent", "Transforms the raw provider response",
                new AgentDefinition.InstructionTemplate("instruction"),
                (agent, context) -> modelRequest(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                response -> new AgentDecision.Complete(
                        new AgentOutput("token=raw-secret", Map.of("handler", true))),
                new AgentGuardrails(List.of(), List.of(redact),
                        AgentOutputGuardrail.Scope.PUBLIC), false);
        Agent agent = new DefinedAgent(definition);

        AgentDecision decision = new AgentRunner(
                TestAgentExecutionService.model(invocation -> result("provider", agent)),
                models, null, null, List.of(agent)).run(agent, context("transform-agent"));

        AgentOutput output = ((AgentDecision.Complete) decision).result();
        assertThat(guardedCandidate).hasValue("token=raw-secret");
        assertThat(output.content()).isEqualTo("token=[REDACTED]");
        assertThat(AgentRunner.publicOutputGuardrailApplied(output)).isTrue();
    }

    @Test
    void diagnosticMetadataCannotForgePublicOutputPolicyEvidence() {
        AgentOutput forged = new AgentOutput("unsafe", Map.of(
                AgentRunner.OUTPUT_GUARDRAIL_APPLIED, true,
                AgentRunner.OUTPUT_GUARDRAIL_SCOPE,
                AgentOutputGuardrail.Scope.PUBLIC.name()));

        assertThat(AgentRunner.publicOutputGuardrailApplied(forged)).isFalse();
    }

    @Test
    void recordsEveryModelAttemptWhenOutputPolicyRequestsRetry() {
        AtomicInteger executions = new AtomicInteger();
        List<AiUsageSnapshot> usage = new java.util.ArrayList<>();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> {
            int attempt = executions.incrementAndGet();
            return new AgentRunResult(new AiMessage.Assistant(attempt == 1 ? "unsafe" : "safe"),
                    List.of(), Optional.of(new AgentRunResult.Usage(attempt, attempt + 1)),
                    new AgentRunResult.RunMetadata(invocation.session().agent().id(),
                            invocation.session().model().id(), null, Map.of()));
        });
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(model());
        AgentOutputGuardrail output = request -> executions.get() == 1
                ? new AgentOutputGuardrail.Result.Retry("rewrite it",
                GuardrailDecision.of("output", "1", GuardrailDecision.Action.RETRY))
                : new AgentOutputGuardrail.Result.Allow(request.candidate(),
                GuardrailDecision.of("output", "1", GuardrailDecision.Action.ALLOW));
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("usage-agent"),
                "Usage Agent", "Counts retry attempts",
                new AgentDefinition.InstructionTemplate("instruction"),
                (agent, context) -> modelRequest(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(), List.of(output)), false);
        AgentWorkflowContext workflow = context("usage-agent", new WorkflowRunControl() {
            @Override public void checkpoint() { }
            @Override public void recordUsage(AiUsageSnapshot snapshot) { usage.add(snapshot); }
            @Override public void registerUsage(java.util.function.Supplier<AiUsageSnapshot> source,
                                                Runnable lateWriteFence) { }
        });

        AgentDecision decision = new AgentRunner(execution, models, null, null,
                List.of(new DefinedAgent(definition))).run(new DefinedAgent(definition), workflow);

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(usage).extracting(AiUsageSnapshot::promptTokens)
                .containsExactly(1L, 2L);
    }

    @Test
    void inputRewriteIsVisibleBeforeAHandlerReturnsSkip() {
        AtomicReference<String> handlerInput = new AtomicReference<>();
        AgentInputGuardrail rewrite = request -> new AgentInputGuardrail.Result.Rewrite(
                new AiMessage.User("rewritten"),
                GuardrailDecision.of("input", "1", GuardrailDecision.Action.REWRITE));
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("skip-rewrite-agent"),
                "Skip Rewrite Agent", "Sees guarded input before local completion",
                new AgentDefinition.InstructionTemplate("instruction"),
                (agent, context) -> {
                    handlerInput.set(context.execution().userMessage().content());
                    return new AgentRunRequest.Skip(new AgentDecision.Complete(
                            new AgentOutput(context.execution().userMessage().content(), Map.of())));
                },
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(rewrite), List.of()), false);

        AgentDecision decision = new AgentRunner(null, List.of(new DefinedAgent(definition)))
                .run(new DefinedAgent(definition), context("skip-rewrite-agent"));

        assertThat(handlerInput).hasValue("rewritten");
        assertThat(((AgentDecision.Complete) decision).result().content()).isEqualTo("rewritten");
    }

    @Test
    void reappliesInputGuardrailsToARequestThatIgnoresTheRewrittenContext() {
        AtomicReference<String> executedInput = new AtomicReference<>();
        AtomicInteger guardrailCalls = new AtomicInteger();
        AgentExecutionService execution = TestAgentExecutionService.model(invocation -> {
            executedInput.set(invocation.request().content());
            return result("safe answer", invocation.session().agent());
        });
        SpringAiModelCatalog models = mock(SpringAiModelCatalog.class);
        when(models.require("model")).thenReturn(model());
        AgentInputGuardrail rewrite = request -> {
            guardrailCalls.incrementAndGet();
            return new AgentInputGuardrail.Result.Rewrite(new AiMessage.User("rewritten"),
                    GuardrailDecision.of("input", "1", GuardrailDecision.Action.REWRITE));
        };
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("final-rewrite-agent"),
                "Final Rewrite Agent", "Validates the concrete execution request",
                new AgentDefinition.InstructionTemplate("instruction"),
                (agent, workflow) -> {
                    if ("prompt".equals(workflow.execution().userMessage().content())) {
                        return new AgentRunRequest.Skip(new AgentDecision.Complete(
                                new AgentOutput("not used", Map.of())));
                    }
                    // Deliberately ignore the rewritten context. The Runner must still
                    // guard the exact request that reaches the model port.
                    return new AgentRunRequest.Model("model", new Agent.Instruction("instruction"),
                            new AiMessage.User("original"), List.of(),
                            workflow.executionScope(ExecutionScope.Purpose.USER_RESPONSE), Map.of());
                },
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(rewrite), List.of()), false);
        Agent agent = new DefinedAgent(definition);

        AgentDecision decision = new AgentRunner(execution, models, null, null, List.of(agent))
                .run(agent, context("final-rewrite-agent"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(executedInput).hasValue("rewritten");
        assertThat(guardrailCalls).hasValue(2);
    }

    @Test
    void recordsAssignedAgentPreflightFailureBeforeARequestExists() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                new ChatRequest("prompt", "request", "preflight-agent", "conversation",
                        null, List.of(), null, "model", null, null),
                List.of(), new AiMessage.User("prompt"), null, recorder,
                false, false, AgentToolPolicy.NONE, 0);
        AiWorkflowPlan.AgentTask task = new AiWorkflowPlan.AgentTask(
                "preflight-agent", "Preflight", "Prepare the assignment.",
                null, null, null, AiWorkflowPlan.ToolAccess.NONE);
        AiWorkflowPlan plan = new AiWorkflowPlan(
                new AiWorkflowPlan.WorkflowDefinition("preflight-workflow", List.of(
                        new AiWorkflowPlan.Member("member", task, null))), null, null);
        AgentWorkflowContext workflow = AgentWorkflowContext.root(execution,
                        new AgentWorkflowContext.Request("request", "conversation", "user",
                                "model", "prompt", false, false, 1, "balanced",
                                null, true, false, false), 1)
                .inWorkflow(plan, new AgentWorkflowContext.Location(
                        "preflight-workflow", "root:preflight", "root", 1, AiWorkflowType.SEQUENTIAL))
                .withAssignment(plan, "member", task, List.of());
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("preflight-agent"), "Preflight Agent", "Fails during preparation",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> { throw new IllegalStateException("prepare failed"); },
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                AgentGuardrails.none(), true));

        assertThatThrownBy(() -> new AgentRunner(null, List.of(agent)).run(agent, workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prepare failed");
        verify(recorder).lifecycle(org.mockito.ArgumentMatchers.eq("subagent_preparing"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
        verify(recorder).lifecycle(org.mockito.ArgumentMatchers.eq("subagent_failed"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
        verify(recorder, times(0)).terminalLifecycle(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void appliesDefinitionOutputPolicyToACompleteSkipDecision() {
        GuardrailDecision rewriteDecision = GuardrailDecision.of(
                "output", "1", GuardrailDecision.Action.REWRITE);
        AgentOutputGuardrail rewrite = request -> new AgentOutputGuardrail.Result.Rewrite(
                new AiMessage.Assistant("safe"),
                rewriteDecision);
        AgentDefinition definition = new AgentDefinition(new Agent.AgentId("skip-agent"),
                "Skip Agent", "Applies policy to a local result",
                new AgentDefinition.InstructionTemplate("instruction"),
                (agent, context) -> new AgentRunRequest.Skip(
                        new AgentDecision.Complete(new AgentOutput("unsafe", Map.of()))),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(), List.of(rewrite)), false);

        AgentDecision decision = new AgentRunner(null, List.of(new DefinedAgent(definition)))
                .run(new DefinedAgent(definition), context("skip-agent"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(((AgentDecision.Complete) decision).result().content()).isEqualTo("safe");
        assertThat(((AgentDecision.Complete) decision).result().metadata())
                .containsEntry(AgentRunner.OUTPUT_GUARDRAIL_APPLIED, true)
                .containsEntry("output_guardrail_decisions", List.of(rewriteDecision.decisionId()));
    }

    @Test
    void appliesDefinitionInputGuardrailsToChatRequestsBeforeChatExecution() {
        AgentExecutionService chat = mock(AgentExecutionService.class);
        ChatExecutionContext execution = executionContext("chat-agent")
                .withUserMessage(new AiMessage.User("original"));
        AiMessage.User input = new AiMessage.User("original");
        when(chat.executeChat(any(AgentChatSession.class))).thenReturn(new AgentChatResult("chat answer"));
        AgentInputGuardrail inputGuardrail = request -> {
            assertThat(request.input().content()).isEqualTo("original");
            return new AgentInputGuardrail.Result.Rewrite(new AiMessage.User("rewritten"),
                    GuardrailDecision.of("input", "1", GuardrailDecision.Action.REWRITE));
        };
        Agent agent = new DefinedAgent(new AgentDefinition(new Agent.AgentId("chat-agent"),
                "chat-agent", "chat-agent", new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Chat(execution),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(inputGuardrail), List.of()), false));
        AgentRunner runner = new AgentRunner(chat, List.of(agent));

        AgentDecision decision = runner.run(agent, context("chat-agent"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        verify(chat).executeChat(any(AgentChatSession.class));
    }

    @Test
    void bindsEachAgentDefinitionInstructionThroughOneSharedRunner() {
        List<AgentChatSession> sessions = new java.util.ArrayList<>();
        AgentExecutionService chat = TestAgentExecutionService.chat(session -> {
            sessions.add(session);
            return new AgentChatResult("answer");
        });
        Agent first = chatAgent("first-agent", "First definition instruction.");
        Agent second = chatAgent("second-agent", "Second definition instruction.");
        AgentRunner runner = new AgentRunner(chat, List.of(first, second));

        runner.run(first, context("first-agent"));
        runner.run(second, context("second-agent"));

        assertThat(sessions).extracting(session -> session.agent().id().value())
                .containsExactly("first-agent", "second-agent");
        assertThat(sessions).extracting(session -> session.instruction().value())
                .containsExactly("First definition instruction.",
                        "Second definition instruction.");
    }

    @Test
    void snapshotsAReloadableDefinitionOncePerInvocation() {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<AgentChatSession> captured = new AtomicReference<>();
        AgentExecutionService chat = TestAgentExecutionService.chat(session -> {
            captured.set(session);
            return new AgentChatResult("answer");
        });
        Agent reloading = () -> {
            int version = loads.incrementAndGet();
            String id = version == 1 ? "snapshot-one" : "snapshot-two";
            return new AgentDefinition(new Agent.AgentId(id), id, id,
                    new AgentDefinition.InstructionTemplate("instruction-" + version),
                    (ignored, context) -> new AgentRunRequest.Chat(context.execution()),
                    org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                    org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                    AgentGuardrails.none(), false);
        };

        new AgentRunner(chat, List.of()).run(reloading, context("snapshot-one"));

        assertThat(loads).hasValue(1);
        assertThat(captured.get().agent().id().value()).isEqualTo("snapshot-one");
        assertThat(captured.get().instruction().value()).isEqualTo("instruction-1");
    }

    @Test
    void appliesDefinitionToolBindingToChatRequestsBeforeChatExecution() {
        AgentExecutionService chat = mock(AgentExecutionService.class);
        ChatExecutionContext execution = executionContext("chat-tools-agent");
        ToolSet tools = new ToolSet(List.of(tool()));
        ToolExecutionGateway gateway = new ToolExecutionGateway(tools,
                mock(ToolGuardrailRegistry.class), List.of(), RequestFence.ALLOW,
                ExecutionObserver.noop(), new ExecutionState(), 4096);
        AgentToolBinding binding = new AgentToolBinding(tools, gateway);
        AtomicReference<ChatExecutionContext> captured = new AtomicReference<>();
        when(chat.executeChat(any(AgentChatSession.class))).thenAnswer(invocation -> {
            AgentChatSession session = invocation.getArgument(0);
            captured.set((ChatExecutionContext) session.context());
            return new AgentChatResult("chat answer");
        });
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("chat-tools-agent"), "chat-tools-agent", "chat tools",
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Chat(execution),
                (ignored, context) -> binding,
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                AgentGuardrails.none(), false));
        AgentRunner runner = new AgentRunner(chat, List.of(agent));

        AgentDecision decision = runner.run(agent, context("chat-tools-agent"));

        assertThat(decision).isInstanceOf(AgentDecision.Complete.class);
        assertThat(captured.get().toolBinding().tools()).isSameAs(binding.tools());
        assertThat(captured.get().toolBinding().gateway()).isNotSameAs(binding.gateway());
        assertThat(captured.get().toolBinding().gateway().enabled()).isTrue();
    }

    @Test
    void resolvesDefinitionToolsOnceAndRefusesToReplayThemOnOutputRetry() {
        AgentExecutionService chat = mock(AgentExecutionService.class);
        ChatExecutionContext execution = executionContext("chat-tool-retry-agent");
        when(chat.executeChat(any(AgentChatSession.class))).thenReturn(new AgentChatResult("unsafe"));
        AgentOutputGuardrail output = request -> new AgentOutputGuardrail.Result.Retry(
                "rewrite without replaying tools",
                GuardrailDecision.of("output", "1", GuardrailDecision.Action.RETRY));
        ToolSet tools = new ToolSet(List.of(tool()));
        ToolExecutionGateway gateway = new ToolExecutionGateway(tools,
                mock(ToolGuardrailRegistry.class), List.of(), RequestFence.ALLOW,
                ExecutionObserver.noop(), new ExecutionState(), 4096);
        AgentToolBinding binding = new AgentToolBinding(tools, gateway);
        AtomicInteger resolutions = new AtomicInteger();
        Agent agent = new DefinedAgent(new AgentDefinition(
                new Agent.AgentId("chat-tool-retry-agent"), "chat-tool-retry-agent",
                "tool-enabled chat retry", new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> new AgentRunRequest.Chat(execution),
                (ignored, context) -> resolutions.incrementAndGet() == 1
                        ? binding : AgentToolBinding.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                new AgentGuardrails(List.of(), List.of(output)), false));

        assertThatThrownBy(() -> new AgentRunner(chat, List.of(agent))
                .run(agent, context("chat-tool-retry-agent")))
                .isInstanceOf(AgentOutputRetryHandoffException.class)
                .satisfies(failure -> assertThat(
                        ((AgentOutputRetryHandoffException) failure)
                                .candidate()).isEqualTo("unsafe"));
        assertThat(resolutions).hasValue(1);
        verify(chat).executeChat(any(AgentChatSession.class));
    }

    private Agent chatAgent(String id, String instruction) {
        return new DefinedAgent(new AgentDefinition(new Agent.AgentId(id), id, id,
                new AgentDefinition.InstructionTemplate(instruction),
                (ignored, context) -> new AgentRunRequest.Chat(context.execution()),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                AgentGuardrails.none(), false));
    }

    private Agent agent(org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler tools,
                        AgentGuardrails guardrails, String id) {
        return new DefinedAgent(new AgentDefinition(new Agent.AgentId(id), id, id,
                new AgentDefinition.InstructionTemplate("instruction"),
                (ignored, context) -> modelRequest(), tools,
                org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseHandler.complete(),
                guardrails, false));
    }

    private AgentRunRequest.Model modelRequest() {
        ExecutionScope scope = new ExecutionScope("request", "conversation", "user", 0,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        return new AgentRunRequest.Model("model", new Agent.Instruction("instruction"),
                new AiMessage.User("input"), List.of(), scope,
                Map.of());
    }

    private AgentRunResult result(String answer, Agent agent) {
        AiMessage.Assistant response = new AiMessage.Assistant(answer);
        return new AgentRunResult(response, List.of(response), Optional.empty(),
                new AgentRunResult.RunMetadata(agent.id(), model().id(), null, Map.of()));
    }

    private AiModel model() {
        return new AiModel(new AiModel.ModelId("model"), new AiModel.ProviderId("provider"),
                AiModel.ModelCapabilities.TEXT_ONLY, AiModel.ContextWindow.UNKNOWN);
    }

    private AiTool tool() {
        return new AiTool() {
            private final ToolSpecification specification = new ToolSpecification(
                    new ToolId("search"), "search", "Search", "{}", "{}",
                    ToolEffect.READ_ONLY);

            @Override public ToolSpecification specification() { return specification; }
            @Override public ToolResult execute(ToolArguments arguments,
                                                 ToolExecutionContext context) {
                return new ToolResult("{}");
            }
        };
    }

    private AgentWorkflowContext context(String agentId) {
        return context(agentId, WorkflowRunControl.NOOP);
    }

    private AgentWorkflowContext context(String agentId, WorkflowRunControl runControl) {
        ChatExecutionContext execution = executionContext(agentId);
        return AgentWorkflowContext.root(execution, new AgentWorkflowContext.Request(
                "request", "conversation", "user", "model", "prompt", false, false,
                1, "balanced", null, false, false, false), 3, runControl);
    }

    private ChatExecutionContext executionContext(String agentId) {
        ChatRequest request = new ChatRequest("prompt", "request", agentId,
                "conversation", null, List.of(), null, "model", null, null);
        return ChatExecutionContext.fromRequest(request, List.of(), new UserMessage("prompt"),
                null, null, false, false);
    }
}

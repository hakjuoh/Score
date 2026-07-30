package org.oagi.score.gateway.http.api.ai_management.tool;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionState;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddleware;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareChain;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareException;
import org.oagi.score.gateway.http.api.ai_management.middleware.MiddlewareState;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutionGatewayTest {

    private final ExecutionScope scope = new ExecutionScope("request", "conversation", "user",
            1, ExecutionScope.Purpose.USER_RESPONSE, List.of());

    @Test
    void appliesBothInputStagesAuthorizationFenceAndOutputBeforeDisclosure() {
        List<String> order = new ArrayList<>();
        AtomicInteger authorizations = new AtomicInteger();
        ExecutionState state = new ExecutionState();
        AiTool tool = tool(AiTool.ToolEffect.MUTATION, arguments -> {
            order.add("execute:" + arguments.json());
            return new AiTool.ToolResult("secret=raw");
        });
        ToolInputGuardrail input = request -> {
            order.add(request.stage().name());
            if (request.stage() == ToolInputGuardrail.Stage.PRE_EXECUTION
                    && "{\"value\":1}".equals(request.arguments().json())) {
                return new ToolInputGuardrail.Result.Rewrite(
                        new AiTool.ToolArguments("{\"value\":2}"),
                        decision(GuardrailDecision.Action.REWRITE));
            }
            return new ToolInputGuardrail.Result.Allow(request.arguments(),
                    decision(GuardrailDecision.Action.ALLOW));
        };
        ToolOutputGuardrail output = request -> {
            order.add("output");
            return new ToolOutputGuardrail.Result.Rewrite(new AiTool.ToolResult("[REDACTED]"),
                    decision(GuardrailDecision.Action.REWRITE));
        };
        ToolGuardrailRegistry registry = new ToolGuardrailRegistry(
                new ToolGuardrailRegistry.Set(List.of(input), List.of(output)), Map.of());
        ToolAuthorizationPolicy authorization = request -> {
            order.add("authorize:" + request.arguments().json());
            authorizations.incrementAndGet();
            return new ToolAuthorizationPolicy.Result.Allow("grant");
        };
        ToolExecutionGateway gateway = new ToolExecutionGateway(new ToolSet(List.of(tool)), registry,
                List.of(authorization), ignored -> order.add("fence"), ExecutionObserver.noop(), state, 1024);

        AiTool.ToolResult result = gateway.execute(tool.specification().id(),
                new AiTool.ToolArguments("{\"value\":1}"), scope);

        assertThat(result.json()).isEqualTo("[REDACTED]");
        assertThat(authorizations).hasValue(2);
        assertThat(order).containsExactly("fence", "PRE_AUTHORIZATION", "authorize:{\"value\":1}",
                "PRE_EXECUTION", "authorize:{\"value\":2}", "PRE_EXECUTION", "fence",
                "execute:{\"value\":2}", "output");
        assertThat(state.completedMutations()).isEqualTo(1);
    }

    @Test
    void outputRefusalPreservesTruthThatMutationCompleted() {
        ExecutionState state = new ExecutionState();
        AiTool mutation = tool(AiTool.ToolEffect.MUTATION,
                ignored -> new AiTool.ToolResult("private result"));
        ToolGuardrailRegistry registry = new ToolGuardrailRegistry(
                new ToolGuardrailRegistry.Set(List.of(request ->
                        new ToolInputGuardrail.Result.Allow(request.arguments(),
                                decision(GuardrailDecision.Action.ALLOW))),
                        List.of(request -> new ToolOutputGuardrail.Result.Refuse(
                                new AiTool.ToolResult("result suppressed"),
                                decision(GuardrailDecision.Action.REFUSE)))), Map.of());
        ToolExecutionGateway gateway = new ToolExecutionGateway(new ToolSet(List.of(mutation)), registry,
                List.of(), null, null, state, 1024);

        assertThat(gateway.execute(mutation.specification().id(),
                new AiTool.ToolArguments("{}"), scope).json()).isEqualTo("result suppressed");
        assertThat(state.completedMutations()).isEqualTo(1);
    }

    @Test
    void outputWriteDoesNotTriggerDataMutationReplayFence() {
        ExecutionState state = new ExecutionState();
        AiTool outputWrite = tool(AiTool.ToolEffect.OUTPUT_WRITE,
                ignored -> new AiTool.ToolResult("artifact created"));
        ToolExecutionGateway gateway = new ToolExecutionGateway(new ToolSet(List.of(outputWrite)),
                passThroughRegistry(request -> new ToolOutputGuardrail.Result.Allow(
                        request.output(), decision(GuardrailDecision.Action.ALLOW))),
                List.of(), null, null, state, 1024);

        assertThat(gateway.execute(outputWrite.specification().id(),
                new AiTool.ToolArguments("{}"), scope).json()).isEqualTo("artifact created");
        assertThat(state.completedToolCalls()).isEqualTo(1);
        assertThat(state.completedMutations()).isZero();
    }

    @Test
    void outputPolicyFailureStillFencesTheCompletedMutationFromRetry() {
        ExecutionState state = new ExecutionState();
        AiTool mutation = tool(AiTool.ToolEffect.MUTATION,
                ignored -> new AiTool.ToolResult("mutation completed"));
        ToolGuardrailRegistry registry = new ToolGuardrailRegistry(
                new ToolGuardrailRegistry.Set(List.of(request ->
                        new ToolInputGuardrail.Result.Allow(request.arguments(),
                                decision(GuardrailDecision.Action.ALLOW))),
                        List.of(request -> {
                            throw new IllegalStateException("output policy unavailable");
                        })), Map.of());
        ToolExecutionGateway gateway = new ToolExecutionGateway(new ToolSet(List.of(mutation)), registry,
                List.of(), null, null, state, 1024);

        AiTool.ToolResult result = gateway.execute(mutation.specification().id(),
                new AiTool.ToolArguments("{}"), scope);

        assertThat(result.json()).contains("TOOL_POLICY_UNAVAILABLE");
        assertThat(result.metadata()).containsEntry("policy_unavailable", true);
        assertThat(state.completedMutations()).isEqualTo(1);
    }

    @Test
    void sideEffectAndExecutionAuthorizationRunInsideTheAtomicFence() {
        java.util.concurrent.atomic.AtomicBoolean insideFence =
                new java.util.concurrent.atomic.AtomicBoolean();
        AiTool mutation = tool(AiTool.ToolEffect.MUTATION, ignored -> {
            assertThat(insideFence).isTrue();
            return new AiTool.ToolResult("completed");
        });
        ToolGuardrailRegistry registry = new ToolGuardrailRegistry(
                new ToolGuardrailRegistry.Set(List.of(request ->
                        new ToolInputGuardrail.Result.Allow(request.arguments(),
                                decision(GuardrailDecision.Action.ALLOW))),
                        List.of(request -> new ToolOutputGuardrail.Result.Allow(
                                request.output(), decision(GuardrailDecision.Action.ALLOW)))), Map.of());
        ToolAuthorizationPolicy authorization = new ToolAuthorizationPolicy() {
            @Override
            public Result authorize(Request request) {
                return new Result.Allow("grant");
            }

            @Override
            public Result beforeExecution(Request request) {
                assertThat(insideFence).isTrue();
                return new Result.Allow("grant");
            }
        };
        ToolExecutionGateway.RequestFence fence = new ToolExecutionGateway.RequestFence() {
            @Override
            public void verifyActive(ExecutionScope ignored) {
            }

            @Override
            public <T> T callIfActive(ExecutionScope ignored,
                                      java.util.function.Supplier<T> action) {
                assertThat(insideFence.compareAndSet(false, true)).isTrue();
                try {
                    return action.get();
                } finally {
                    insideFence.set(false);
                }
            }
        };
        ToolExecutionGateway gateway = new ToolExecutionGateway(
                new ToolSet(List.of(mutation)), registry, List.of(authorization), fence,
                ExecutionObserver.noop(), new ExecutionState(), 1024);

        assertThat(gateway.execute(mutation.specification().id(),
                new AiTool.ToolArguments("{}"), scope).json()).isEqualTo("completed");
        assertThat(insideFence).isFalse();
    }

    @Test
    void middlewareReplacementStillPassesTheMandatoryOutputGuardrail() {
        AtomicInteger executions = new AtomicInteger();
        AiTool tool = tool(AiTool.ToolEffect.READ_ONLY, ignored -> {
            executions.incrementAndGet();
            return new AiTool.ToolResult("raw");
        });
        ToolGuardrailRegistry registry = passThroughRegistry(request ->
                new ToolOutputGuardrail.Result.Rewrite(
                        new AiTool.ToolResult("checked:" + request.output().json()),
                        decision(GuardrailDecision.Action.REWRITE)));
        AiMiddleware replacement = new NamedMiddleware("replacement") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                return new AiTool.ToolResult("safe replacement");
            }
        };
        ToolExecutionGateway gateway = gateway(tool, registry, replacement);

        AiTool.ToolResult result = gateway.execute(tool.specification().id(),
                new AiTool.ToolArguments("{}"), scope);

        assertThat(result.json()).isEqualTo("checked:safe replacement");
        assertThat(executions).hasValue(0);
    }

    @Test
    void middlewareCannotReplayAMutationOrChangeAuthorizedArguments() {
        AtomicInteger executions = new AtomicInteger();
        ExecutionState state = new ExecutionState();
        AiTool mutation = tool(AiTool.ToolEffect.MUTATION, ignored -> {
            executions.incrementAndGet();
            return new AiTool.ToolResult("changed");
        });
        ToolGuardrailRegistry registry = passThroughRegistry(request ->
                new ToolOutputGuardrail.Result.Allow(request.output(),
                        decision(GuardrailDecision.Action.ALLOW)));
        AiMiddleware replay = new NamedMiddleware("replay") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                next.call(context);
                return next.call(context);
            }
        };
        AiMiddlewareChain replayChain = chain(replay);
        ToolExecutionGateway replayGateway = new ToolExecutionGateway(
                new ToolSet(List.of(mutation)), registry, List.of(), null, null, state,
                1024, replayChain, new MiddlewareState());

        assertThatThrownBy(() -> replayGateway.execute(mutation.specification().id(),
                new AiTool.ToolArguments("{}"), scope))
                .isInstanceOf(AiMiddlewareException.class)
                .rootCause().hasMessageContaining("more than once");
        assertThat(executions).hasValue(1);
        assertThat(state.completedMutations()).isEqualTo(1);

        AiMiddleware rewrite = new NamedMiddleware("rewrite") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                return next.call(context.withArguments(new AiTool.ToolArguments("{\"other\":true}")));
            }
        };
        assertThatThrownBy(() -> gateway(mutation, registry, rewrite).execute(
                mutation.specification().id(), new AiTool.ToolArguments("{}"), scope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after authorization");
    }

    private ToolExecutionGateway gateway(AiTool tool, ToolGuardrailRegistry registry,
                                         AiMiddleware middleware) {
        return new ToolExecutionGateway(new ToolSet(List.of(tool)), registry, List.of(),
                null, null, new ExecutionState(), 1024, chain(middleware),
                new MiddlewareState());
    }

    private AiMiddlewareChain chain(AiMiddleware middleware) {
        ScoreAiProperties.Middleware settings = new ScoreAiProperties.Middleware();
        settings.setProfiles(Map.of("default", List.of(middleware.id())));
        return new AiMiddlewareChain(settings, List.of(middleware));
    }

    private ToolGuardrailRegistry passThroughRegistry(ToolOutputGuardrail output) {
        ToolInputGuardrail input = request -> new ToolInputGuardrail.Result.Allow(
                request.arguments(), decision(GuardrailDecision.Action.ALLOW));
        return new ToolGuardrailRegistry(
                new ToolGuardrailRegistry.Set(List.of(input), List.of(output)), Map.of());
    }

    private abstract static class NamedMiddleware implements AiMiddleware {
        private final String id;
        private NamedMiddleware(String id) { this.id = id; }
        @Override public String id() { return id; }
    }

    private AiTool tool(AiTool.ToolEffect effect,
                        java.util.function.Function<AiTool.ToolArguments, AiTool.ToolResult> operation) {
        return new AiTool() {
            private final ToolSpecification specification = new ToolSpecification(
                    new ToolId("test-tool"), "test-tool", "test", "{}", "{}", effect);
            @Override public ToolSpecification specification() { return specification; }
            @Override public ToolResult execute(ToolArguments arguments, ToolExecutionContext context) {
                return operation.apply(arguments);
            }
        };
    }

    private GuardrailDecision decision(GuardrailDecision.Action action) {
        return GuardrailDecision.of("test-policy", "1", action);
    }
}

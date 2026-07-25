package org.oagi.score.gateway.http.api.ai_management.tool;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionState;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolOutputGuardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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

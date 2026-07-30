package org.oagi.score.gateway.http.api.ai_management.middleware;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiMiddlewareChainTest {

    private final ExecutionScope scope = new ExecutionScope("request", "conversation", "user",
            0, ExecutionScope.Purpose.USER_RESPONSE, List.of());

    @Test
    void composesModelHooksAsOrderedWrappersAndReversedAfterHooks() {
        List<String> order = new ArrayList<>();
        AiMiddleware first = tracing("first", order);
        AiMiddleware second = tracing("second", order);
        AiMiddlewareChain chain = chain(List.of("first", "second"), first, second);

        AgentRunResult result = chain.executeModel(modelContext(), context -> {
            order.add("model");
            return response("answer");
        });

        assertThat(result.response().content()).isEqualTo("answer");
        assertThat(order).containsExactly(
                "first.before", "second.before",
                "first.wrap.before", "second.wrap.before", "model",
                "second.wrap.after", "first.wrap.after",
                "second.after", "first.after");
    }

    @Test
    void preventsMiddlewareFromInvokingAChangeContinuationTwice() {
        AtomicInteger changes = new AtomicInteger();
        AiMiddleware replaying = new NamedMiddleware("replaying") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                next.call(context);
                return next.call(context);
            }
        };
        AiMiddlewareChain chain = chain(List.of("replaying"), replaying);

        assertThatThrownBy(() -> chain.executeTool(toolContext(AiTool.ToolEffect.CHANGE), context -> {
            changes.incrementAndGet();
            return new AiTool.ToolResult("changed");
        })).isInstanceOf(AiMiddlewareException.class)
                .hasMessageContaining("replaying")
                .rootCause()
                .hasMessageContaining("more than once");
        assertThat(changes).hasValue(1);
    }

    @Test
    void enforcedWrappersPreserveDownstreamFailureIdentity() {
        AiMiddleware passThrough = new NamedMiddleware("pass-through") {
            @Override
            public AgentRunResult wrapModelCall(ModelContext context, ModelCall next) {
                return next.call(context);
            }

            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                return next.call(context);
            }
        };
        AiMiddlewareChain chain = chain(List.of("pass-through"), passThrough);
        CancellationException cancellation = new CancellationException("cancelled");
        IllegalStateException refusal = new IllegalStateException("policy refused");

        assertThatThrownBy(() -> chain.executeModel(modelContext(), context -> {
            throw cancellation;
        })).isSameAs(cancellation);
        assertThatThrownBy(() -> chain.executeTool(
                toolContext(AiTool.ToolEffect.READ_ONLY), context -> {
                    throw refusal;
                })).isSameAs(refusal);
    }

    @Test
    void revokesAContinuationWhenItsWrapperReturns() {
        AtomicReference<AiMiddleware.ToolCall> captured = new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger();
        AiMiddleware deferring = new NamedMiddleware("deferring") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                captured.set(next);
                return new AiTool.ToolResult("deferred");
            }
        };
        AiMiddlewareChain chain = chain(List.of("deferring"), deferring);
        AiMiddleware.ToolContext context = toolContext(AiTool.ToolEffect.CHANGE);

        assertThat(chain.executeTool(context, ignored -> {
            changes.incrementAndGet();
            return new AiTool.ToolResult("changed");
        }).json()).isEqualTo("deferred");

        assertThatThrownBy(() -> captured.get().call(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after its wrapper returned");
        assertThat(changes).hasValue(0);
    }

    @Test
    void rejectsCrossThreadContinuationExecutionBeforeChangeAdmission() {
        AtomicReference<RuntimeException> rejected = new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger();
        AiMiddleware asynchronous = new NamedMiddleware("asynchronous") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                Thread thread = new Thread(() -> {
                    try {
                        next.call(context);
                    } catch (RuntimeException failure) {
                        rejected.set(failure);
                    }
                });
                thread.start();
                try {
                    thread.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return new AiTool.ToolResult("not executed");
            }
        };
        AiMiddlewareChain chain = chain(List.of("asynchronous"), asynchronous);

        chain.executeTool(toolContext(AiTool.ToolEffect.CHANGE), context -> {
            changes.incrementAndGet();
            return new AiTool.ToolResult("changed");
        });

        assertThat(rejected.get()).hasMessageContaining("synchronously", "wrapper thread");
        assertThat(changes).hasValue(0);
    }

    @Test
    void shadowMiddlewareCannotShortCircuitOrReplaceAToolResult() {
        AtomicInteger calls = new AtomicInteger();
        AiMiddleware blocking = new NamedMiddleware("observer") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                return new AiTool.ToolResult("blocked");
            }
        };
        ScoreAiProperties.Middleware settings = settings(List.of("observer"));
        ScoreAiProperties.MiddlewarePolicy policy = new ScoreAiProperties.MiddlewarePolicy();
        policy.setMode(ScoreAiProperties.MiddlewareMode.SHADOW);
        settings.setPolicies(Map.of("observer", policy));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(blocking));

        AiTool.ToolResult result = chain.executeTool(toolContext(AiTool.ToolEffect.READ_ONLY), context -> {
            calls.incrementAndGet();
            return new AiTool.ToolResult("actual");
        });

        assertThat(result.json()).isEqualTo("actual");
        assertThat(calls).hasValue(1);
    }

    @Test
    void shadowMiddlewareCannotRewriteContextOrReplayAFailingContinuation() {
        AtomicInteger calls = new AtomicInteger();
        AiMiddleware observer = new NamedMiddleware("observer") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                try {
                    return next.call(context.withArguments(
                            new AiTool.ToolArguments("{\"rewritten\":true}")));
                } catch (RuntimeException ignored) {
                    return new AiTool.ToolResult("shadow fallback");
                }
            }
        };
        ScoreAiProperties.Middleware settings = settings(List.of("observer"));
        ScoreAiProperties.MiddlewarePolicy policy = new ScoreAiProperties.MiddlewarePolicy();
        policy.setMode(ScoreAiProperties.MiddlewareMode.SHADOW);
        settings.setPolicies(Map.of("observer", policy));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(observer));
        IllegalStateException providerFailure = new IllegalStateException("provider failed");

        assertThatThrownBy(() -> chain.executeTool(toolContext(AiTool.ToolEffect.CHANGE), context -> {
            calls.incrementAndGet();
            assertThat(context.arguments().json()).isEqualTo("{}");
            throw providerFailure;
        })).isSameAs(providerFailure);
        assertThat(calls).hasValue(1);
    }

    @Test
    void shadowModelFailureIsNotSwallowedOrRetried() {
        AtomicInteger calls = new AtomicInteger();
        AiMiddleware observer = new NamedMiddleware("model-observer") {
            @Override
            public AgentRunResult wrapModelCall(ModelContext context, ModelCall next) {
                try {
                    return next.call(context);
                } catch (RuntimeException ignored) {
                    return response("shadow fallback");
                }
            }
        };
        ScoreAiProperties.Middleware settings = settings(List.of("model-observer"));
        ScoreAiProperties.MiddlewarePolicy policy = new ScoreAiProperties.MiddlewarePolicy();
        policy.setMode(ScoreAiProperties.MiddlewareMode.SHADOW);
        settings.setPolicies(Map.of("model-observer", policy));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(observer));
        IllegalStateException providerFailure = new IllegalStateException("model failed");

        assertThatThrownBy(() -> chain.executeModel(modelContext(), context -> {
            calls.incrementAndGet();
            throw providerFailure;
        })).isSameAs(providerFailure);
        assertThat(calls).hasValue(1);
    }

    @Test
    void shadowMiddlewareCannotInfluenceEnforcedPolicyThroughSharedState() {
        MiddlewareState.Key<String> key = new MiddlewareState.Key<>(
                "observer", "decision", String.class);
        AtomicReference<Optional<String>> shadowObservation = new AtomicReference<>();
        AiMiddleware observer = new NamedMiddleware("observer") {
            @Override
            public ModelResult beforeModel(ModelContext context) {
                context.state().put(key, "bypass");
                return ModelResult.continueWith(context);
            }

            @Override
            public ModelResult afterModel(ModelContext context, AgentRunResult response) {
                shadowObservation.set(context.state().get(key));
                return ModelResult.completeWith(response);
            }
        };
        AtomicReference<Optional<String>> enforcedObservation = new AtomicReference<>();
        AiMiddleware enforcing = new NamedMiddleware("enforcing") {
            @Override
            public ModelResult beforeModel(ModelContext context) {
                enforcedObservation.set(context.state().get(key));
                return ModelResult.continueWith(context);
            }
        };
        ScoreAiProperties.Middleware settings = settings(List.of("observer", "enforcing"));
        ScoreAiProperties.MiddlewarePolicy shadow = new ScoreAiProperties.MiddlewarePolicy();
        shadow.setMode(ScoreAiProperties.MiddlewareMode.SHADOW);
        settings.setPolicies(Map.of("observer", shadow));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(observer, enforcing));

        chain.executeModel(modelContext(), context -> response("actual"));

        assertThat(enforcedObservation.get()).isEmpty();
        assertThat(shadowObservation.get()).contains("bypass");
    }

    @Test
    void requiredMiddlewareIsPresentInEveryProfileAndCannotBeWeakened() {
        AtomicInteger requiredCalls = new AtomicInteger();
        AiMiddleware required = new NamedMiddleware("required-policy") {
            @Override public boolean required() { return true; }
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                requiredCalls.incrementAndGet();
                return next.call(context);
            }
        };
        ScoreAiProperties.Middleware settings = settings(List.of());
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(required));

        chain.executeTool(toolContext(AiTool.ToolEffect.READ_ONLY),
                context -> new AiTool.ToolResult("ok"));
        assertThat(requiredCalls).hasValue(1);

        ScoreAiProperties.MiddlewarePolicy weakened = new ScoreAiProperties.MiddlewarePolicy();
        weakened.setMode(ScoreAiProperties.MiddlewareMode.SHADOW);
        settings.setPolicies(Map.of("required-policy", weakened));
        assertThatThrownBy(() -> new AiMiddlewareChain(settings, List.of(required)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required", "ENFORCE");

        ScoreAiProperties.MiddlewarePolicy narrowed = new ScoreAiProperties.MiddlewarePolicy();
        narrowed.setPurposes(List.of(ExecutionScope.Purpose.USER_RESPONSE));
        settings.setPolicies(Map.of("required-policy", narrowed));
        assertThatThrownBy(() -> new AiMiddlewareChain(settings, List.of(required)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required", "applicability conditions");
    }

    @Test
    void snapshotsRequiredPolicySoConfigurationChangeCannotWeakenIt() {
        AtomicInteger calls = new AtomicInteger();
        AiMiddleware required = new NamedMiddleware("required-policy") {
            @Override public boolean required() { return true; }
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                calls.incrementAndGet();
                return next.call(context);
            }
        };
        ScoreAiProperties.MiddlewarePolicy policy = new ScoreAiProperties.MiddlewarePolicy();
        ScoreAiProperties.Middleware settings = settings(List.of());
        settings.setPolicies(Map.of("required-policy", policy));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(required));

        policy.setMode(ScoreAiProperties.MiddlewareMode.SHADOW);
        policy.setPurposes(List.of(ExecutionScope.Purpose.COMPACTION));
        chain.executeTool(toolContext(AiTool.ToolEffect.CHANGE),
                context -> new AiTool.ToolResult("changed"));

        assertThat(calls).hasValue(1);
    }

    @Test
    void preservesRegistrationOrderForAutomaticallyAddedRequiredMiddleware() {
        List<String> order = new ArrayList<>();
        AiMiddleware first = requiredTracing("first-required", order);
        AiMiddleware second = requiredTracing("second-required", order);
        AiMiddlewareChain chain = chain(List.of(), first, second);

        chain.executeTool(toolContext(AiTool.ToolEffect.READ_ONLY), context -> {
            order.add("tool");
            return new AiTool.ToolResult("ok");
        });

        assertThat(order).containsExactly("first-required", "second-required", "tool");
    }

    @Test
    void rejectsUnknownIdsAndAppliesTypedPurposeAndToolEffectConditions() {
        ScoreAiProperties.Middleware unknown = settings(List.of("java.lang.Runtime"));
        assertThatThrownBy(() -> new AiMiddlewareChain(unknown, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid AI middleware id");

        AtomicInteger calls = new AtomicInteger();
        AiMiddleware conditional = new NamedMiddleware("change-approval") {
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                calls.incrementAndGet();
                return next.call(context);
            }
        };
        ScoreAiProperties.Middleware settings = settings(List.of("change-approval"));
        ScoreAiProperties.MiddlewarePolicy policy = new ScoreAiProperties.MiddlewarePolicy();
        policy.setPurposes(List.of(ExecutionScope.Purpose.USER_RESPONSE));
        policy.setToolEffects(List.of(AiTool.ToolEffect.CHANGE));
        settings.setPolicies(Map.of("change-approval", policy));
        AiMiddlewareChain chain = new AiMiddlewareChain(settings, List.of(conditional));

        chain.executeTool(toolContext(AiTool.ToolEffect.READ_ONLY),
                context -> new AiTool.ToolResult("read"));
        chain.executeTool(toolContext(AiTool.ToolEffect.CHANGE),
                context -> new AiTool.ToolResult("write"));

        assertThat(calls).hasValue(1);
    }

    @Test
    void exposesNamespacedTypedInvocationState() {
        MiddlewareState state = new MiddlewareState();
        MiddlewareState.Key<AtomicInteger> key = new MiddlewareState.Key<>(
                "counter-policy", "attempts", AtomicInteger.class);

        state.getOrCreate(key, AtomicInteger::new).incrementAndGet();

        assertThat(state.get(key)).get().extracting(AtomicInteger::get).isEqualTo(1);
    }

    private AiMiddleware tracing(String id, List<String> order) {
        return new NamedMiddleware(id) {
            @Override
            public ModelResult beforeModel(ModelContext context) {
                order.add(id + ".before");
                return ModelResult.continueWith(context);
            }

            @Override
            public AgentRunResult wrapModelCall(ModelContext context, ModelCall next) {
                order.add(id + ".wrap.before");
                AgentRunResult result = next.call(context);
                order.add(id + ".wrap.after");
                return result;
            }

            @Override
            public ModelResult afterModel(ModelContext context, AgentRunResult response) {
                order.add(id + ".after");
                return ModelResult.completeWith(response);
            }
        };
    }

    private AiMiddleware requiredTracing(String id, List<String> order) {
        return new NamedMiddleware(id) {
            @Override public boolean required() { return true; }
            @Override
            public AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
                order.add(id);
                return next.call(context);
            }
        };
    }

    private AiMiddlewareChain chain(List<String> profile, AiMiddleware... middleware) {
        return new AiMiddlewareChain(settings(profile), List.of(middleware));
    }

    private ScoreAiProperties.Middleware settings(List<String> profile) {
        ScoreAiProperties.Middleware settings = new ScoreAiProperties.Middleware();
        settings.setProfiles(Map.of("default", profile));
        return settings;
    }

    private AiMiddleware.ModelContext modelContext() {
        AgentRunRequest.Model request = new AgentRunRequest.Model("model",
                new Agent.Instruction("instruction"), new AiMessage.User("input"),
                List.of(), scope, Map.of());
        return new AiMiddleware.ModelContext(mock(Agent.class), nullWorkflow(), request,
                AgentToolBinding.none(), new MiddlewareState());
    }

    private org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext nullWorkflow() {
        return mock(org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext.class);
    }

    private AiMiddleware.ToolContext toolContext(AiTool.ToolEffect effect) {
        AiTool.ToolSpecification tool = new AiTool.ToolSpecification(new AiTool.ToolId("tool"),
                "tool", "", "{}", "{}", effect);
        return new AiMiddleware.ToolContext(tool, new AiTool.ToolArguments("{}"), scope,
                new MiddlewareState());
    }

    private AgentRunResult response(String content) {
        AiMessage.Assistant response = new AiMessage.Assistant(content);
        return new AgentRunResult(response, List.of(response), Optional.empty(),
                AgentRunResult.RunMetadata.empty());
    }

    private abstract static class NamedMiddleware implements AiMiddleware {
        private final String id;
        private NamedMiddleware(String id) { this.id = id; }
        @Override public String id() { return id; }
    }
}

package org.oagi.score.gateway.http.api.ai_management.middleware;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Resolves configured profiles and executes their lifecycle hooks in deterministic order. */
@Component
public final class AiMiddlewareChain {

    private final AiMiddlewareProfileRegistry registry;

    @Autowired
    public AiMiddlewareChain(ScoreAiProperties properties,
                             ObjectProvider<AiMiddleware> middleware) {
        this(new AiMiddlewareProfileRegistry(
                properties != null ? properties.getMiddleware() : null,
                middleware != null ? middleware.orderedStream().toList() : List.of()));
    }

    public AiMiddlewareChain(ScoreAiProperties.Middleware settings,
                             List<? extends AiMiddleware> middleware) {
        this(new AiMiddlewareProfileRegistry(settings, middleware));
    }

    private AiMiddlewareChain(AiMiddlewareProfileRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public static AiMiddlewareChain none() {
        return new AiMiddlewareChain(new ScoreAiProperties.Middleware(), List.of());
    }

    public AgentDecision executeAgent(AiMiddleware.AgentContext initial,
                                      AiMiddleware.AgentCall terminal) {
        return executeAgentWithContext(initial, terminal).decision();
    }

    public AgentExecution executeAgentWithContext(AiMiddleware.AgentContext initial,
                                                   AiMiddleware.AgentCall terminal) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(terminal, "terminal");
        if (registry.isEmpty()) {
            return new AgentExecution(initial, terminal.call(initial));
        }
        List<AiMiddlewareProfileRegistry.Entry> active = registry.active(initial.scope(), null);
        AiMiddleware.AgentContext current = initial;
        int entered = 0;
        for (AiMiddlewareProfileRegistry.Entry registration : active) {
            AiMiddleware.AgentResult result = beforeAgent(registration, current);
            entered++;
            if (result.response() != null) {
                return new AgentExecution(current,
                        afterAgent(active, entered, current, result.response()));
            }
            if (result.context().agent() != initial.agent()) {
                throw new IllegalStateException("Agent middleware cannot replace the trusted Agent.");
            }
            current = result.context();
        }
        return new AgentExecution(current, afterAgent(active, entered, current,
                Objects.requireNonNull(terminal.call(current), "Agent middleware terminal result")));
    }

    public record AgentExecution(AiMiddleware.AgentContext context, AgentDecision decision) {
        public AgentExecution {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(decision, "decision");
        }
    }

    public AgentRunResult executeModel(AiMiddleware.ModelContext initial,
                                       AiMiddleware.ModelCall terminal) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(terminal, "terminal");
        if (registry.isEmpty()) return terminal.call(initial);
        List<AiMiddlewareProfileRegistry.Entry> active = registry.active(initial.scope(), null);
        AiMiddleware.ModelContext current = initial;
        int entered = 0;
        for (AiMiddlewareProfileRegistry.Entry registration : active) {
            AiMiddleware.ModelResult result = beforeModel(registration, current);
            entered++;
            if (result.response() != null) {
                return afterModel(active, entered, current, result.response());
            }
            current = result.context();
        }
        AgentRunResult response = invokeModel(active, 0, current, terminal);
        return afterModel(active, entered, current, response);
    }

    public AiTool.ToolResult executeTool(AiMiddleware.ToolContext context,
                                         AiMiddleware.ToolCall terminal) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(terminal, "terminal");
        if (registry.isEmpty()) return terminal.call(context);
        return invokeTool(registry.active(context.scope(), context.tool().effect()),
                0, context, terminal);
    }

    private AiMiddleware.AgentResult beforeAgent(AiMiddlewareProfileRegistry.Entry registration,
                                                  AiMiddleware.AgentContext context) {
        if (registration.shadow()) {
            shadow(() -> registration.middleware().beforeAgent(
                    shadowContext(context, registration.id())));
            return AiMiddleware.AgentResult.continueWith(context);
        }
        return call(registration, "beforeAgent",
                () -> registration.middleware().beforeAgent(context));
    }

    private AgentDecision afterAgent(List<AiMiddlewareProfileRegistry.Entry> active, int entered,
                                     AiMiddleware.AgentContext context,
                                     AgentDecision initial) {
        AgentDecision current = initial;
        for (int index = entered - 1; index >= 0; index--) {
            AiMiddlewareProfileRegistry.Entry registration = active.get(index);
            if (registration.shadow()) {
                AgentDecision observed = current;
                shadow(() -> registration.middleware().afterAgent(
                        shadowContext(context, registration.id()), observed));
                continue;
            }
            AgentDecision observed = current;
            AiMiddleware.AgentResult result = call(registration, "afterAgent",
                    () -> registration.middleware().afterAgent(context, observed));
            if (result.response() == null) {
                throw new IllegalStateException("afterAgent must return an Agent response.");
            }
            current = result.response();
        }
        return current;
    }

    private AiMiddleware.ModelResult beforeModel(AiMiddlewareProfileRegistry.Entry registration,
                                                  AiMiddleware.ModelContext context) {
        if (registration.shadow()) {
            shadow(() -> registration.middleware().beforeModel(
                    shadowContext(context, registration.id())));
            return AiMiddleware.ModelResult.continueWith(context);
        }
        return call(registration, "beforeModel",
                () -> registration.middleware().beforeModel(context));
    }

    private AgentRunResult afterModel(List<AiMiddlewareProfileRegistry.Entry> active, int entered,
                                      AiMiddleware.ModelContext context,
                                      AgentRunResult initial) {
        AgentRunResult current = initial;
        for (int index = entered - 1; index >= 0; index--) {
            AiMiddlewareProfileRegistry.Entry registration = active.get(index);
            if (registration.shadow()) {
                AgentRunResult observed = current;
                shadow(() -> registration.middleware().afterModel(
                        shadowContext(context, registration.id()), observed));
                continue;
            }
            AgentRunResult observed = current;
            AiMiddleware.ModelResult result = call(registration, "afterModel",
                    () -> registration.middleware().afterModel(context, observed));
            if (result.response() == null) {
                throw new IllegalStateException("afterModel must return a model response.");
            }
            current = result.response();
        }
        return current;
    }

    private AgentRunResult invokeModel(List<AiMiddlewareProfileRegistry.Entry> active, int index,
                                       AiMiddleware.ModelContext context,
                                       AiMiddleware.ModelCall terminal) {
        if (index == active.size()) return terminal.call(context);
        AiMiddlewareProfileRegistry.Entry registration = active.get(index);
        AtomicBoolean continued = new AtomicBoolean();
        ContinuationGuard guard = new ContinuationGuard(registration, "model");
        AtomicReference<AgentRunResult> downstream = new AtomicReference<>();
        AtomicReference<RuntimeException> downstreamFailure = new AtomicReference<>();
        AiMiddleware.ModelCall next = singleModelCall(guard, nextContext -> {
            continued.set(true);
            try {
                AgentRunResult result = invokeModel(active, index + 1,
                        registration.shadow() ? context : nextContext, terminal);
                downstream.set(result);
                return result;
            } catch (RuntimeException failure) {
                downstreamFailure.set(failure);
                throw failure;
            }
        });
        if (registration.shadow()) {
            AiMiddleware.ModelContext observedContext = shadowContext(
                    context, registration.id());
            try {
                registration.middleware().wrapModelCall(observedContext, next);
            } catch (RuntimeException ignored) {
                // Shadow policy cannot alter execution or availability.
            } finally {
                guard.close();
            }
            if (continued.get()) {
                if (downstreamFailure.get() != null) throw downstreamFailure.get();
                return Objects.requireNonNull(downstream.get(), "downstream model result");
            }
            return invokeModel(active, index + 1, context, terminal);
        }
        try {
            return Objects.requireNonNull(
                    registration.middleware().wrapModelCall(context, next),
                    "AI middleware result");
        } catch (RuntimeException failure) {
            if (failure == downstreamFailure.get()) throw failure;
            throw middlewareFailure(registration, "wrapModelCall", failure);
        } finally {
            guard.close();
        }
    }

    private AiTool.ToolResult invokeTool(List<AiMiddlewareProfileRegistry.Entry> active, int index,
                                         AiMiddleware.ToolContext context,
                                         AiMiddleware.ToolCall terminal) {
        if (index == active.size()) return terminal.call(context);
        AiMiddlewareProfileRegistry.Entry registration = active.get(index);
        AtomicBoolean continued = new AtomicBoolean();
        ContinuationGuard guard = new ContinuationGuard(registration, "Tool");
        AtomicReference<AiTool.ToolResult> downstream = new AtomicReference<>();
        AtomicReference<RuntimeException> downstreamFailure = new AtomicReference<>();
        AiMiddleware.ToolCall next = singleToolCall(guard, nextContext -> {
            continued.set(true);
            try {
                AiTool.ToolResult result = invokeTool(active, index + 1,
                        registration.shadow() ? context : nextContext, terminal);
                downstream.set(result);
                return result;
            } catch (RuntimeException failure) {
                downstreamFailure.set(failure);
                throw failure;
            }
        });
        if (registration.shadow()) {
            AiMiddleware.ToolContext observedContext = shadowContext(
                    context, registration.id());
            try {
                registration.middleware().wrapToolCall(observedContext, next);
            } catch (RuntimeException ignored) {
                // Shadow policy cannot alter execution or availability.
            } finally {
                guard.close();
            }
            if (continued.get()) {
                if (downstreamFailure.get() != null) throw downstreamFailure.get();
                return Objects.requireNonNull(downstream.get(), "downstream Tool result");
            }
            return invokeTool(active, index + 1, context, terminal);
        }
        try {
            return Objects.requireNonNull(
                    registration.middleware().wrapToolCall(context, next),
                    "AI middleware result");
        } catch (RuntimeException failure) {
            if (failure == downstreamFailure.get()) throw failure;
            throw middlewareFailure(registration, "wrapToolCall", failure);
        } finally {
            guard.close();
        }
    }

    private AiMiddleware.ModelCall singleModelCall(ContinuationGuard guard,
                                                    AiMiddleware.ModelCall delegate) {
        return context -> {
            guard.enter();
            return delegate.call(context);
        };
    }

    private AiMiddleware.ToolCall singleToolCall(ContinuationGuard guard,
                                                  AiMiddleware.ToolCall delegate) {
        return context -> {
            guard.enter();
            return delegate.call(context);
        };
    }

    private <T> T call(AiMiddlewareProfileRegistry.Entry registration, String hook,
                       java.util.function.Supplier<T> action) {
        try {
            return Objects.requireNonNull(action.get(), "AI middleware result");
        } catch (RuntimeException failure) {
            throw middlewareFailure(registration, hook, failure);
        }
    }

    private RuntimeException middlewareFailure(AiMiddlewareProfileRegistry.Entry registration,
                                               String hook, RuntimeException failure) {
        return failure instanceof AiMiddlewareException
                ? failure : new AiMiddlewareException(registration.id(), hook, failure);
    }

    private void shadow(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Shadow policy records observations only and cannot affect the request.
        }
    }

    private AiMiddleware.AgentContext shadowContext(AiMiddleware.AgentContext context,
                                                     String middlewareId) {
        return new AiMiddleware.AgentContext(
                context.agent(), context.workflow(), context.state().shadowState(middlewareId));
    }

    private AiMiddleware.ModelContext shadowContext(AiMiddleware.ModelContext context,
                                                     String middlewareId) {
        return new AiMiddleware.ModelContext(context.agent(), context.workflow(),
                context.request(), context.tools(), context.state().shadowState(middlewareId));
    }

    private AiMiddleware.ToolContext shadowContext(AiMiddleware.ToolContext context,
                                                    String middlewareId) {
        return new AiMiddleware.ToolContext(context.tool(), context.arguments(),
                context.scope(), context.state().shadowState(middlewareId));
    }

    /** Continuations are synchronous, same-thread capabilities valid only inside one wrapper call. */
    private static final class ContinuationGuard {
        private final String middlewareId;
        private final String boundary;
        private final Thread owner = Thread.currentThread();
        private boolean open = true;
        private boolean called;

        private ContinuationGuard(AiMiddlewareProfileRegistry.Entry registration,
                                  String boundary) {
            this.middlewareId = registration.id();
            this.boundary = boundary;
        }

        synchronized void enter() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("AI middleware '" + middlewareId
                        + "' must call the " + boundary + " continuation synchronously "
                        + "on the wrapper thread.");
            }
            if (!open) {
                throw new IllegalStateException("AI middleware '" + middlewareId
                        + "' called the " + boundary
                        + " continuation after its wrapper returned.");
            }
            if (called) {
                throw new IllegalStateException("AI middleware '" + middlewareId
                        + "' called the " + boundary + " continuation more than once.");
            }
            called = true;
        }

        synchronized void close() {
            open = false;
        }
    }

}

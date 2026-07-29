package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareChain;
import org.oagi.score.gateway.http.api.ai_management.middleware.MiddlewareState;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;

/** Request-scoped Tool resources selected by an Agent definition. */
public record AgentToolBinding(ToolSet tools, ToolExecutionGateway gateway,
                               boolean transportInherited) {

    public AgentToolBinding(ToolSet tools, ToolExecutionGateway gateway) {
        this(tools, gateway, false);
    }

    public AgentToolBinding {
        tools = tools != null ? tools : ToolSet.empty();
        gateway = gateway != null ? gateway : ToolExecutionGateway.disabled();
        if (transportInherited && (!tools.isEmpty() || gateway.enabled())) {
            throw new IllegalArgumentException(
                    "A transport-inherited Agent Tool binding cannot contain owned tools.");
        }
        if (!tools.isEmpty() && !gateway.enabled()) {
            throw new IllegalArgumentException(
                    "A non-empty Agent Tool set requires an enabled execution gateway.");
        }
    }

    public static AgentToolBinding none() {
        return new AgentToolBinding(ToolSet.empty(), ToolExecutionGateway.disabled());
    }

    /** Leaves the request transport's already-authorized Tool session unchanged. */
    public static AgentToolBinding inheritTransport() {
        return new AgentToolBinding(ToolSet.empty(), ToolExecutionGateway.disabled(), true);
    }

    /** Installs the request's selected middleware on definition-owned Tool execution. */
    public AgentToolBinding withMiddleware(AiMiddlewareChain middleware,
                                           MiddlewareState state) {
        if (transportInherited || tools.isEmpty()) return this;
        return new AgentToolBinding(tools, gateway.withMiddleware(middleware, state), false);
    }

    /** Compatibility overload for callers that only need a cancellation fence. */
    @Deprecated(forRemoval = false)
    public AgentToolBinding withExecutionFence(AgentExecutionRecorder recorder,
                                               Runnable checkpoint) {
        return withExecutionFence(recorder, checkpoint, () -> { });
    }

    /** Couples owned Tool execution to cancellation, activity, and callback fences. */
    public AgentToolBinding withExecutionFence(AgentExecutionRecorder recorder,
                                               Runnable checkpoint,
                                               Runnable progress) {
        if (transportInherited || tools.isEmpty()) return this;
        AgentExecutionRecorder runRecorder = recorder != null
                ? recorder : AgentExecutionRecorder.noop();
        Runnable runCheckpoint = checkpoint != null ? checkpoint : () -> { };
        Runnable runProgress = progress != null ? progress : () -> { };
        return new AgentToolBinding(tools,
                gateway.withAdditionalFence(new ToolExecutionGateway.RequestFence() {
                    @Override
                    public void verifyActive(
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope scope) {
                        runCheckpoint.run();
                        runRecorder.verifyActive();
                    }

                    @Override
                    public <T> T callIfActive(
                            org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope scope,
                            java.util.function.Supplier<T> action) {
                        // Admission is the final checkpoint. Once admitted, the
                        // Tool side effect may finish even if cancellation follows,
                        // and it runs outside the recorder lock so that a
                        // server-to-client callback can still reach the recorder.
                        runRecorder.callWhileActive(() -> {
                            runCheckpoint.run();
                            runProgress.run();
                            return null;
                        });
                        try {
                            return action.get();
                        } finally {
                            runProgress.run();
                        }
                    }
                }), false);
    }
}

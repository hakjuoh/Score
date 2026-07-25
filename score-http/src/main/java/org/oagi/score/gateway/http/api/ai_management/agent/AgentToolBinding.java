package org.oagi.score.gateway.http.api.ai_management.agent;

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

    /** Couples owned Tool execution to the run deadline and terminal callback fence. */
    public AgentToolBinding withExecutionFence(AgentExecutionRecorder recorder,
                                               Runnable checkpoint) {
        if (transportInherited || tools.isEmpty()) return this;
        AgentExecutionRecorder runRecorder = recorder != null
                ? recorder : AgentExecutionRecorder.noop();
        Runnable runCheckpoint = checkpoint != null ? checkpoint : () -> { };
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
                        return runRecorder.callWhileActive(() -> {
                            // Admission is the final checkpoint. Once admitted, the
                            // Tool side effect may finish even if cancellation follows.
                            runCheckpoint.run();
                            return action.get();
                        });
                    }
                }), false);
    }
}

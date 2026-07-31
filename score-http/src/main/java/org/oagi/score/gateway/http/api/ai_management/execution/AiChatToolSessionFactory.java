package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareChain;
import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeToolGuard;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiPlatformToolProvider;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Selects, guards, budgets, and binds the executable Tool set for one model run. */
final class AiChatToolSessionFactory {

    private static final ToolCallingAdvisor DIRECT_TOOL_CALLING_ADVISOR =
            ToolCallingAdvisor.builder().build();

    private final ToolSearchToolCallingAdvisor toolSearchAdvisor;
    private final AiChangeToolGuard changeGuard;
    private final ToolGuardrailRegistry toolGuardrails;
    private final SpringAiCallbackToolSetAdapter callbackToolAdapter;
    private final SpringAiToolAdapter springAiToolAdapter;
    private final org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry requests;
    private final AiMiddlewareChain middleware;
    private final AiPlatformToolProvider platformTools;
    private final boolean toolSearchEnabled;
    private final boolean coordinatedApprovals;
    private final ExecutionObserver observer;

    AiChatToolSessionFactory(ToolSearchToolCallingAdvisor toolSearchAdvisor,
                             AiChangeToolGuard changeGuard,
                             ToolGuardrailRegistry toolGuardrails,
                             SpringAiCallbackToolSetAdapter callbackToolAdapter,
                             SpringAiToolAdapter springAiToolAdapter,
                             org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry requests,
                             AiMiddlewareChain middleware,
                             AiPlatformToolProvider platformTools,
                             boolean toolSearchEnabled, boolean coordinatedApprovals,
                             ExecutionObserver observer) {
        this.toolSearchAdvisor = toolSearchAdvisor;
        this.changeGuard = changeGuard;
        this.toolGuardrails = toolGuardrails;
        this.callbackToolAdapter = callbackToolAdapter;
        this.springAiToolAdapter = springAiToolAdapter;
        this.requests = requests;
        this.middleware = middleware;
        this.platformTools = platformTools;
        this.toolSearchEnabled = toolSearchEnabled;
        this.coordinatedApprovals = coordinatedApprovals;
        this.observer = observer;
    }

    AiChatToolSetup prepare(AiChatExecutor.Context context,
                            ConnectCenterMcpClientFactory.McpSession mcp,
                            ExecutionState executionState, Runnable progress,
                            WorkflowRunControl runControl, AiTrajectoryRecorder recorder,
                            ChatClient.Builder builder, long tokenLimit,
                            ExecutionScope scope) {
        if (context.agentToolBinding() != null) {
            configureBoundTools(context, builder, recorder, tokenLimit, scope);
            return AiChatToolSetup.empty();
        }
        if (context.toolPolicy() == AiChatExecutor.ToolPolicy.NONE) {
            return AiChatToolSetup.empty();
        }
        var mcpCallbacks = mcp != null && mcp.tools() != null ? mcp.tools()
                : (org.springframework.ai.tool.ToolCallbackProvider) () ->
                new org.springframework.ai.tool.ToolCallback[0];
        Set<String> readOnlyNames = mcp != null ? mcp.readOnlyToolNames() : Set.of();
        boolean commonGateway = toolGuardrails != null
                && callbackToolAdapter != null && springAiToolAdapter != null;
        ToolSet localTools = context.toolPolicy() == AiChatExecutor.ToolPolicy.FULL
                && platformTools != null ? platformTools.tools(context.requester(),
                scope) : ToolSet.empty();
        Set<String> nonChangeNames = java.util.stream.Stream.concat(readOnlyNames.stream(),
                        localTools.values().stream().filter(tool -> tool.specification().effect()
                                == AiTool.ToolEffect.READ_ONLY
                                || tool.specification().effect() == AiTool.ToolEffect.OUTPUT_WRITE)
                                .map(tool -> tool.specification().name()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        recorder.readOnlyToolNames(nonChangeNames);
        if ((mcpCallbacks.getToolCallbacks().length > 0 || !localTools.isEmpty())
                && !commonGateway) {
            throw new IllegalStateException(
                    "The mandatory Tool execution gateway is not configured.");
        }
        AiChangeToolGuard.GuardedToolSession guarded = guardedSession(
                context, mcp, runControl, recorder, mcpCallbacks, nonChangeNames, localTools,
                commonGateway);
        var guardedTools = context.toolPolicy() == AiChatExecutor.ToolPolicy.READ_ONLY
                ? changeGuard != null ? changeGuard.readOnly(mcpCallbacks, readOnlyNames)
                : (org.springframework.ai.tool.ToolCallbackProvider) () ->
                new org.springframework.ai.tool.ToolCallback[0]
                : guarded != null ? guarded : mcpCallbacks;
        ToolSet coreTools = callbackToolAdapter != null
                ? callbackToolAdapter.adapt(guardedTools, readOnlyNames,
                mcp != null ? mcp.toolCatalog() : List.of()).plus(localTools) : localTools;
        org.springframework.ai.tool.ToolCallbackProvider executable = bindGateway(
                context, executionState, progress, recorder, builder, tokenLimit,
                guarded, coreTools, scope);
        String catalog = configureDiscovery(context, mcp, builder, coreTools);
        return new AiChatToolSetup(guarded, executable, catalog);
    }

    private AiChangeToolGuard.GuardedToolSession guardedSession(
            AiChatExecutor.Context context, ConnectCenterMcpClientFactory.McpSession mcp,
            WorkflowRunControl runControl, AiTrajectoryRecorder recorder,
            org.springframework.ai.tool.ToolCallbackProvider mcpCallbacks,
            Set<String> nonChangeNames, ToolSet localTools, boolean commonGateway) {
        if (context.toolPolicy() != AiChatExecutor.ToolPolicy.FULL
                || mcp == null && localTools.isEmpty() || changeGuard == null) return null;
        return commonGateway
                ? changeGuard.authorizationSession(context.request(), context.requester(),
                coordinatedApprovals ? ignored -> { } : recorder::changeConfirmationRequired,
                mcpCallbacks, nonChangeNames, runControl)
                : changeGuard.session(context.request(), context.requester(),
                coordinatedApprovals ? ignored -> { } : recorder::changeConfirmationRequired,
                mcpCallbacks, nonChangeNames, runControl);
    }

    private org.springframework.ai.tool.ToolCallbackProvider bindGateway(
            AiChatExecutor.Context context, ExecutionState executionState, Runnable progress,
            AiTrajectoryRecorder recorder, ChatClient.Builder builder, long tokenLimit,
            AiChangeToolGuard.GuardedToolSession guarded, ToolSet coreTools,
            ExecutionScope scope) {
        if (coreTools.isEmpty()) return null;
        long rawByteLimit = tokenLimit == Long.MAX_VALUE ? 16L * 1024L * 1024L
                : Math.max(4096L, Math.min(16L * 1024L * 1024L, tokenLimit * 4L));
        ToolExecutionGateway gateway = new ToolExecutionGateway(coreTools, toolGuardrails,
                guarded != null ? List.of(guarded) : List.of(), requestFence(recorder, progress),
                observer, executionState, rawByteLimit, middleware, context.middlewareState());
        // Recording stays outside the gateway so trajectory/UI see only bounded,
        // output-guarded results.
        var executable = recorder.recordingTools(springAiToolAdapter.adapt(coreTools, gateway,
                scope), tokenLimit);
        builder.defaultTools(executable);
        return executable;
    }

    private String configureDiscovery(AiChatExecutor.Context context,
                                      ConnectCenterMcpClientFactory.McpSession mcp,
                                      ChatClient.Builder builder, ToolSet coreTools) {
        // A confirmed continuation already has a server-bound target. Direct callbacks let it
        // resume without rediscovering the Tool while the same guard still protects changes.
        if (context.request().changeConfirmation() != null || !toolSearchEnabled) {
            builder.defaultAdvisors(DIRECT_TOOL_CALLING_ADVISOR);
            return McpToolCatalog.render(coreTools, mcp != null ? mcp.toolCatalog() : List.of());
        }
        // READ_ONLY was reduced to the server-declared safe set before this point. Keeping that
        // registry deferred avoids injecting every read schema into delegated worker prompts.
        builder.defaultAdvisors(toolSearchAdvisor);
        return "";
    }

    private ToolExecutionGateway.RequestFence requestFence(AiTrajectoryRecorder recorder,
                                                            Runnable progress) {
        return new ToolExecutionGateway.RequestFence() {
            @Override
            public void verifyActive(ExecutionScope scope) {
                recorder.verifyActive();
                if (Thread.currentThread().isInterrupted() || requests != null
                        && requests.shouldDiscardResult(scope.requestId())) {
                    throw cancelled();
                }
            }

            @Override
            public <T> T callIfActive(ExecutionScope scope, java.util.function.Supplier<T> action) {
                if (Thread.currentThread().isInterrupted()) throw cancelled();
                progress.run();
                if (requests != null) requests.admitToolExecution(scope.requestId());
                else recorder.verifyActive();
                try {
                    return Objects.requireNonNull(action, "action").get();
                } finally {
                    progress.run();
                }
            }
        };
    }

    private void configureBoundTools(AiChatExecutor.Context context, ChatClient.Builder builder,
                                     AiTrajectoryRecorder recorder, long tokenLimit,
                                     ExecutionScope scope) {
        AgentToolBinding binding = Objects.requireNonNull(context.agentToolBinding(),
                "Agent Tool binding");
        if (binding.tools().isEmpty()) return;
        if (!binding.gateway().enabled()) {
            throw new IllegalStateException("An Agent Tool binding requires an enabled gateway.");
        }
        builder.defaultTools(recorder.recordingTools(springAiToolAdapter.adapt(
                binding.tools(), binding.gateway(), scope), tokenLimit));
    }

    private CancellationException cancelled() {
        return new CancellationException("The assistant request stopped before Tool execution.");
    }
}

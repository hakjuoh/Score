package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.middleware.MiddlewareState;

/** Maps the neutral Chat context into the provider's private execution state. */
final class SpringAiExecutionContextMapper {

    private SpringAiExecutionContextMapper() {
    }

    static AiChatExecutor.Context toProvider(ChatExecutionContext context) {
        return toProvider(context, new MiddlewareState());
    }

    static AiChatExecutor.Context toProvider(ChatExecutionContext context,
                                             MiddlewareState middlewareState) {
        return new AiChatExecutor.Context(context.request(),
                context.history().stream().map(SpringAiMessageAdapter::toProvider).toList(),
                SpringAiUserMessageAdapter.toSpring(context.userMessage()),
                context.requester(),
                AgentExecutionRecorderAdapter.providerRecorder(context.recorder()),
                context.toolsEnabled(), context.streamVisibleContent(),
                toProviderPolicy(context.toolPolicy()), context.agentDepth(),
                context.approvalScope(), context.approvalWaitLifecycle(), context.agentId(),
                context.executionPurpose(), context.guardrailDecisionIds(),
                context.workflowObservationContext(), context.toolBinding(),
                middlewareState);
    }

    private static AiChatExecutor.ToolPolicy toProviderPolicy(AgentToolPolicy policy) {
        return switch (policy != null ? policy : AgentToolPolicy.NONE) {
            case NONE -> AiChatExecutor.ToolPolicy.NONE;
            case READ_ONLY -> AiChatExecutor.ToolPolicy.READ_ONLY;
            case FULL -> AiChatExecutor.ToolPolicy.FULL;
        };
    }
}

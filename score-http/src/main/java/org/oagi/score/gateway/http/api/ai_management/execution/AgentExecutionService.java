package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatSession;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;

/** The single application port for every Agent model or Chat invocation. */
@FunctionalInterface
public interface AgentExecutionService {
    AgentRunResult execute(AgentInvocation invocation);

    /**
     * Executes a provider-backed Chat session through the same application port.
     * The default preserves the model-only functional contract for integrations
     * that do not support Chat sessions.
     */
    default AgentChatResult executeChat(AgentChatSession session) {
        throw new UnsupportedOperationException(
                "Chat execution is not configured for this Agent execution service.");
    }
}

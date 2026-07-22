package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.springframework.stereotype.Component;

/** Single application-port implementation; the legacy facade retains Spring request mechanics. */
@Component
public final class SpringAiAgentExecutionService implements AgentExecutionService {

    private final AiChatExecutor executor;

    public SpringAiAgentExecutionService(AiChatExecutor executor) {
        this.executor = executor;
    }

    @Override
    public AgentRunResult execute(AgentInvocation invocation) {
        return executor.executeAgent(invocation);
    }
}

package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;

/** The single application port for every model invocation. */
@FunctionalInterface
public interface AgentExecutionService {
    AgentRunResult execute(AgentInvocation invocation);
}

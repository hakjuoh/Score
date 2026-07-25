package org.oagi.score.gateway.http.api.ai_management.support;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatSession;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;

import java.util.Objects;
import java.util.function.Function;

/** Test-only adapters that make the two-method execution port explicit. */
public final class TestAgentExecutionService {

    private TestAgentExecutionService() {
    }

    public static AgentExecutionService model(
            Function<AgentInvocation, AgentRunResult> execution) {
        Objects.requireNonNull(execution, "execution");
        return new AgentExecutionService() {
            @Override
            public AgentRunResult execute(AgentInvocation invocation) {
                return execution.apply(invocation);
            }

            @Override
            public AgentChatResult executeChat(AgentChatSession session) {
                throw new AssertionError("The model-only test adapter received a Chat turn.");
            }
        };
    }

    public static AgentExecutionService chat(
            Function<AgentChatSession, AgentChatResult> execution) {
        Objects.requireNonNull(execution, "execution");
        return new AgentExecutionService() {
            @Override
            public AgentRunResult execute(AgentInvocation invocation) {
                throw new AssertionError("The Chat-only test adapter received a model turn.");
            }

            @Override
            public AgentChatResult executeChat(AgentChatSession session) {
                return execution.apply(session);
            }
        };
    }
}

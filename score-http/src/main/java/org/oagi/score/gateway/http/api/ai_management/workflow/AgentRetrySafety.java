package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;

/** Detects whether an output retry could duplicate a tool side effect or approval. */
final class AgentRetrySafety {
    private AgentRetrySafety() { }

    static Activity snapshot(AgentWorkflowContext context, AgentRunRequest request) {
        AgentExecutionRecorder recorder = recorder(context, request);
        return new Activity(recorder.completedToolCallCount(),
                recorder.executedDomainToolCallCount(), recorder.pendingApprovalCount());
    }

    static boolean changed(Activity before, Activity after) {
        return after.completedCalls() > before.completedCalls()
                || after.executedDomainCalls() > before.executedDomainCalls()
                || after.pendingApprovals() > before.pendingApprovals()
                || after.pendingApprovals() > 0L;
    }

    static boolean wouldReplay(AgentToolBinding binding, AgentRunRequest request) {
        if (request instanceof AgentRunRequest.Model) {
            return !binding.transportInherited() && !binding.tools().isEmpty();
        }
        if (request instanceof AgentRunRequest.Chat chat) {
            return (!binding.transportInherited() && !binding.tools().isEmpty())
                    || chat.context().toolsEnabled()
                    || chat.context().toolPolicy() != AgentToolPolicy.NONE;
        }
        return false;
    }

    private static AgentExecutionRecorder recorder(AgentWorkflowContext context,
                                                    AgentRunRequest request) {
        return request instanceof AgentRunRequest.Chat chat
                ? chat.context().recorder() : context.execution().recorder();
    }

    record Activity(long completedCalls, long executedDomainCalls, long pendingApprovals) { }
}

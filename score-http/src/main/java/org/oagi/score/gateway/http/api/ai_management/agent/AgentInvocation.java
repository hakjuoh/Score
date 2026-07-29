package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One immutable execution request for a definition-bound Agent session. */
public record AgentInvocation(AgentRunId runId, AgentSession session, AiMessage.User request,
                              List<AiMessage> history, ExecutionScope scope,
                              ToolExecutionGateway tools,
                              Map<String, Object> observationContext,
                              AgentExecutionRecorder recorder) {

    public AgentInvocation(AgentRunId runId, AgentSession session, AiMessage.User request,
                           List<AiMessage> history, ExecutionScope scope,
                           ToolExecutionGateway tools,
                           Map<String, Object> observationContext) {
        this(runId, session, request, history, scope, tools, observationContext,
                AgentExecutionRecorder.noop());
    }

    public AgentInvocation(AgentRunId runId, AgentSession session, AiMessage.User request,
                           List<AiMessage> history, ExecutionScope scope,
                           ToolExecutionGateway tools) {
        this(runId, session, request, history, scope, tools, Map.of(),
                AgentExecutionRecorder.noop());
    }

    public AgentInvocation {
        runId = runId != null ? runId : AgentRunId.create();
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        history = history != null ? List.copyOf(history) : List.of();
        Objects.requireNonNull(scope, "scope");
        tools = tools != null ? tools : ToolExecutionGateway.disabled();
        observationContext = observationContext != null
                ? Map.copyOf(observationContext) : Map.of();
        recorder = recorder != null ? recorder : AgentExecutionRecorder.noop();
    }

    public record AgentRunId(String value) {
        public AgentRunId {
            value = Objects.requireNonNull(value, "run id").strip();
            if (value.isEmpty()) throw new IllegalArgumentException("run id is required");
        }
        public static AgentRunId create() { return new AgentRunId(UUID.randomUUID().toString()); }
    }
}

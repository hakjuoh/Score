package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Adapts the provider-owned trajectory recorder to the Agent execution port. */
public final class AgentExecutionRecorderAdapter implements AgentExecutionRecorder {

    private final AiTrajectoryRecorder delegate;

    private AgentExecutionRecorderAdapter(AiTrajectoryRecorder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static AgentExecutionRecorder of(AiTrajectoryRecorder recorder) {
        return recorder != null ? new AgentExecutionRecorderAdapter(recorder)
                : AgentExecutionRecorder.noop();
    }

    public static AiTrajectoryRecorder providerRecorder(AgentExecutionRecorder recorder) {
        if (recorder instanceof AgentExecutionRecorderAdapter adapter) {
            return adapter.delegate;
        }
        throw new IllegalArgumentException(
                "The Chat execution adapter requires a provider-backed Agent recorder.");
    }

    @Override
    public AgentExecutionRecorder fork(Map<String, Object> namespace) {
        return new AgentExecutionRecorderAdapter(delegate.fork(namespace));
    }

    @Override
    public AgentExecutionRecorder forkSubagent(String agentId, String assignment,
                                               Map<String, Object> namespace) {
        return new AgentExecutionRecorderAdapter(
                delegate.forkSubagent(agentId, assignment, namespace));
    }

    @Override
    public String conversationId() {
        return delegate.conversationId();
    }

    @Override
    public Map<String, Object> observationContext() {
        return delegate.observationContext();
    }

    @Override
    public AiUsageSnapshot usageSnapshot() {
        return delegate.usageSnapshot();
    }

    @Override
    public void verifyActive() {
        delegate.verifyActive();
    }

    @Override
    public <T> T callWhileActive(Supplier<T> action) {
        return delegate.callWhileActive(action);
    }

    @Override
    public void sealAgainstLateCallbacks() {
        delegate.sealAgainstLateCallbacks();
    }

    @Override
    public void sealUsageAccounting() {
        delegate.sealUsageAccounting();
    }

    @Override
    public void recordFanOutUsage(String fanoutId, String executionKind,
                                  List<AiUsageSnapshot> agents) {
        delegate.recordFanOutUsage(fanoutId, executionKind, agents);
    }

    @Override
    public void recordSettledFanOutUsage(String fanoutId, String executionKind,
                                         List<AiUsageSnapshot> agents) {
        delegate.recordSettledFanOutUsage(fanoutId, executionKind, agents);
    }

    @Override
    public void lifecycle(String subtype, String content, Map<String, Object> metadata) {
        delegate.lifecycle(subtype, content, metadata);
    }

    @Override
    public void terminalLifecycle(String subtype, String content,
                                  Map<String, Object> metadata) {
        delegate.terminalLifecycle(subtype, content, metadata);
    }

    @Override
    public long completedToolCallCount() {
        return delegate.completedToolCallCount();
    }

    @Override
    public long executedDomainToolCallCount() {
        return delegate.executedDomainToolCallCount();
    }

    @Override
    public long pendingApprovalCount() {
        return delegate.pendingApprovalCount();
    }
}

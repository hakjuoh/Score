package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Provider-neutral observation and usage port visible to Agent definitions and
 * Workflows. Provider-specific trajectory implementations stay behind the
 * execution adapter.
 */
public interface AgentExecutionRecorder {

    AgentExecutionRecorder fork(Map<String, Object> namespace);

    AgentExecutionRecorder forkSubagent(String agentId, String assignment,
                                        Map<String, Object> namespace);

    String conversationId();

    Map<String, Object> observationContext();

    AiUsageSnapshot usageSnapshot();

    /** Fails fast when a provider callback arrives after the run was terminalized. */
    void verifyActive();

    /**
     * Linearizes an externally visible action with terminal sealing. Either the
     * action starts while this recorder is active, or it is rejected entirely.
     */
    <T> T callWhileActive(Supplier<T> action);

    void sealAgainstLateCallbacks();

    /** Closes the accounting-only grace window for an admitted provider call. */
    default void sealUsageAccounting() {
    }

    void recordFanOutUsage(String fanoutId, String executionKind,
                           List<AiUsageSnapshot> agents);

    /**
     * Persists final usage for attempts admitted before termination. Unlike ordinary
     * callbacks, accounting may complete after the recorder's disclosure fence closes.
     */
    default void recordSettledFanOutUsage(String fanoutId, String executionKind,
                                          List<AiUsageSnapshot> agents) {
        recordFanOutUsage(fanoutId, executionKind, agents);
    }

    void lifecycle(String subtype, String content, Map<String, Object> metadata);

    void terminalLifecycle(String subtype, String content, Map<String, Object> metadata);

    long completedToolCallCount();

    long executedDomainToolCallCount();

    long pendingApprovalCount();

    static AgentExecutionRecorder noop() {
        return NoOp.INSTANCE;
    }

    final class NoOp implements AgentExecutionRecorder {
        private static final NoOp INSTANCE = new NoOp();

        private NoOp() {
        }

        @Override
        public AgentExecutionRecorder fork(Map<String, Object> namespace) {
            return this;
        }

        @Override
        public AgentExecutionRecorder forkSubagent(String agentId, String assignment,
                                                   Map<String, Object> namespace) {
            return this;
        }

        @Override
        public String conversationId() {
            return "unknown";
        }

        @Override
        public Map<String, Object> observationContext() {
            return Map.of();
        }

        @Override
        public AiUsageSnapshot usageSnapshot() {
            return null;
        }

        @Override
        public void verifyActive() {
        }

        @Override
        public <T> T callWhileActive(Supplier<T> action) {
            return java.util.Objects.requireNonNull(action, "action").get();
        }

        @Override
        public void sealAgainstLateCallbacks() {
        }

        @Override
        public void sealUsageAccounting() {
        }

        @Override
        public void recordFanOutUsage(String fanoutId, String executionKind,
                                      List<AiUsageSnapshot> agents) {
        }

        @Override
        public void recordSettledFanOutUsage(String fanoutId, String executionKind,
                                             List<AiUsageSnapshot> agents) {
        }

        @Override
        public void lifecycle(String subtype, String content, Map<String, Object> metadata) {
        }

        @Override
        public void terminalLifecycle(String subtype, String content,
                                      Map<String, Object> metadata) {
        }

        @Override
        public long completedToolCallCount() {
            return 0L;
        }

        @Override
        public long executedDomainToolCallCount() {
            return 0L;
        }

        @Override
        public long pendingApprovalCount() {
            return 0L;
        }
    }
}

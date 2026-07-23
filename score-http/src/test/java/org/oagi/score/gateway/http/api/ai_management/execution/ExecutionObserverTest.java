package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionObserverTest {

    @Test
    void lifecycleObservationKeepsOperationalMetadataAndDropsSensitivePayloads() {
        ExecutionScope scope = new ExecutionScope("request", "conversation", "user", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiExecutionLifecycle lifecycle = AiExecutionLifecycle.from(AiExecutionEvent.tool(
                "failed", "private result", "call-1", "lookup", 2, Map.of(
                        "duration_ms", 12L,
                        "failure_type", "java.lang.IllegalStateException",
                        "toolDetail", "private arguments and result",
                        "argumentsSummary", "private arguments")));

        AiExecutionLifecycle restored = AiExecutionLifecycle.from(lifecycle.observation(scope))
                .orElseThrow();

        assertThat(restored.toolCallId()).isEqualTo("call-1");
        assertThat(restored.metadata())
                .containsEntry("duration_ms", 12L)
                .containsEntry("failure_type", "java.lang.IllegalStateException")
                .doesNotContainKeys("toolDetail", "argumentsSummary");
        assertThat(restored.toString()).doesNotContain("private");
    }

    @Test
    void canonicalLifecycleConstructionSanitizesProviderTextAndIdentifiers() {
        String secret = "SECRET-provider-response-body-user@example.test";
        AiExecutionLifecycle lifecycle = new AiExecutionLifecycle(
                "detail", "provider_retry", "unsafe call id " + secret,
                "unsafe tool " + secret, -1L, Map.of(
                "reason", secret,
                "failure_class", "unsafe failure " + secret,
                "mcp_server_name", "unsafe server " + secret,
                "toolDetail", secret));

        assertThat(lifecycle.toolCallId()).isEqualTo("unknown");
        assertThat(lifecycle.toolName()).isEqualTo("unknown");
        assertThat(lifecycle.toolCallSequence()).isNull();
        assertThat(lifecycle.metadata())
                .doesNotContainKeys("reason", "toolDetail")
                .containsEntry("failure_class", "unknown")
                .containsEntry("mcp_server_name", "unknown");
        assertThat(lifecycle.toString()).doesNotContain(secret);
    }

    @Test
    void emptyAndFailingOptionalObserversNeverChangeExecution() {
        ExecutionScope scope = new ExecutionScope("request", "conversation", "user", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AtomicBoolean secondObserved = new AtomicBoolean();
        AtomicBoolean failureIsolated = new AtomicBoolean();
        ExecutionObserver composite = ExecutionObserver.composite(List.of(
                ignored -> { throw new IllegalStateException("optional sink failed"); },
                ignored -> secondObserved.set(true)), ignored -> failureIsolated.set(true));

        composite.observe(ExecutionObservation.of("agent.completed", scope, Map.of()));
        ExecutionObserver.composite(List.of()).observe(
                ExecutionObservation.of("agent.completed", scope, Map.of()));

        assertThat(secondObserved).isTrue();
        assertThat(failureIsolated).isTrue();
    }
}

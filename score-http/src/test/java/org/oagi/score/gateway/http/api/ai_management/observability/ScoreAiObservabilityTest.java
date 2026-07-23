package org.oagi.score.gateway.http.api.ai_management.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.AiExecutionLifecycle;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreAiObservabilityTest {

    private InMemorySpanExporter spans;
    private InMemoryMetricReader metrics;
    private SdkTracerProvider tracerProvider;
    private SdkMeterProvider meterProvider;
    private ScoreAiObservability observability;

    @BeforeEach
    void setUp() {
        spans = InMemorySpanExporter.create();
        metrics = InMemoryMetricReader.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build();
        meterProvider = SdkMeterProvider.builder().registerMetricReader(metrics).build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();
        observability = new ScoreAiObservability(openTelemetry, "3.6.0-test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        meterProvider.close();
    }

    @Test
    void correlatesAiOperationsWithoutPuttingRequestIdentityInMetricLabels() {
        ChatRequest request = new ChatRequest("private prompt", "request-1", null,
                "conversation-1", null, List.of(), null,
                "claude-fable-5", "high", "ask");
        String upstreamTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 7,
                "00-" + upstreamTrace + "-00f067aa0ba902b7-01", null);
        turn.executionStarted();

        Map<String, Object> correlation = observability.correlation("request-1");
        assertThat(correlation).containsEntry("trace_id", upstreamTrace)
                .containsEntry("service_version", "3.6.0-test")
                .containsKey("root_span_id");

        ScoreAiObservability.ModelCall model = observability.startModelCall(
                "request-1", "claude-fable-5", "anthropic", "assistant");
        model.firstToken();
        model.complete(responseWithUsage());

        AiExecutionObservationExporter exporter = new AiExecutionObservationExporter(observability);
        ExecutionScope executionScope = new ExecutionScope("request-1", "conversation-1", "user-1",
                7, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        exporter.observe(AiExecutionLifecycle.from(AiExecutionEvent.tool(
                        "started", "sensitive narration", "tool-call-1",
                        "get_business_contexts", 1))
                .observation(executionScope));
        exporter.observe(AiExecutionLifecycle.from(AiExecutionEvent.tool(
                        "completed", "sensitive result", "tool-call-1",
                        "get_business_contexts", 1,
                        Map.of("duration_ms", 12L, "success", true)))
                .observation(executionScope));
        exporter.observe(AiExecutionLifecycle.from(AiExecutionEvent.detail(
                        "provider_retry", "provider message is private", Map.of(
                                "attempt", 1, "max_attempts", 3, "delay_millis", 25,
                                "status_code", 429, "failure_class", "RateLimitException")))
                .observation(executionScope));
        observability.recordGuardrails("request-1", "agent_output", List.of(
                GuardrailDecision.of("safe-output", "1", GuardrailDecision.Action.REWRITE)));
        turn.complete("COMPLETED", null);

        List<SpanData> exported = spans.getFinishedSpanItems();
        SpanData root = span(exported, "score.ai.turn");
        SpanData modelSpan = span(exported, "score.ai.model");
        SpanData toolSpan = span(exported, "score.ai.tool");
        assertThat(root.getTraceId()).isEqualTo(upstreamTrace);
        assertThat(modelSpan.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(modelSpan.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(toolSpan.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(toolSpan.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(toolSpan.getAttributes().get(
                AttributeKey.stringKey("mcp.server.name"))).isNull();
        assertThat(modelSpan.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(10L);
        assertThat(exported).allSatisfy(span -> assertThat(span.getAttributes().asMap().keySet())
                .noneMatch(key -> key.getKey().contains("prompt")
                        || key.getKey().contains("content")
                        || key.getKey().contains("result")));

        var exportedMetrics = metrics.collectAllMetrics();
        assertThat(exportedMetrics).extracting(metric -> metric.getName())
                .contains("score.ai.turn.duration", "score.ai.turn.time_to_first_token",
                        "score.ai.model.tokens", "score.ai.tool.duration",
                        "score.ai.guardrail.decisions", "score.ai.cost");
        assertThat(exportedMetrics).flatExtracting(metric -> metric.getData().getPoints())
                .allSatisfy(point -> assertThat(point.getAttributes().asMap().keySet())
                        .noneMatch(key -> key.getKey().contains("request.id")
                                || key.getKey().contains("conversation.id")
                                || key.getKey().contains("enduser.id")
                                || key.getKey().contains("agent.id")
                                || key.getKey().contains("call.id")));
        assertThat(observability.correlation("request-1")).isEmpty();
        long active = metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals("score.ai.requests.active"))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                .mapToLong(LongPointData::getValue).sum();
        assertThat(active).isZero();
    }

    @Test
    void returnsEmptyCorrelationWithoutARequestId() {
        assertThat(observability.correlation(null)).isEmpty();
    }

    @Test
    void recordsTruncationOnTheToolSpanBeforeItEnds() {
        ChatRequest request = new ChatRequest("prompt", "request-truncated", null,
                "conversation-truncated", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        observe("request-truncated", AiExecutionEvent.tool(
                "started", "", "tool-call-truncated", "large_result", 1));

        try (var ignored = observability.makeToolCurrent(
                "request-truncated", "tool-call-truncated")) {
            observe("request-truncated", AiExecutionEvent.detail(
                    "tool_output_truncated", "", Map.of(
                            "toolName", "large_result",
                            "originalUtf8Bytes", 1024,
                            "returnedUtf8Bytes", 256,
                            "mcp", false)));
            observe("request-truncated", AiExecutionEvent.tool(
                    "completed", "", "tool-call-truncated", "large_result", 1,
                    Map.of("duration_ms", 12L, "success", true,
                            "result_truncated", true)));
        }
        turn.complete("COMPLETED", null);

        SpanData tool = span(spans.getFinishedSpanItems(), "score.ai.tool");
        assertThat(tool.getAttributes().get(
                AttributeKey.booleanKey("score.ai.tool.result_truncated"))).isTrue();
        assertThat(tool.getEvents()).extracting(event -> event.getName())
                .contains("score.ai.tool.output.truncated");
        long truncations = metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals("score.ai.tool.truncations"))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                .mapToLong(LongPointData::getValue).sum();
        assertThat(truncations).isEqualTo(1L);
    }

    @Test
    void closesIncompleteChildSpansWhenTheTurnIsCancelled() {
        ChatRequest request = new ChatRequest("prompt", "request-2", null,
                "conversation-2", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        observe("request-2", AiExecutionEvent.tool(
                "started", "", "tool-call-2", "search", 1));
        observe("request-2", AiExecutionEvent.detail(
                "parallel_workflow_started", "", Map.of(
                        "fanout_id", "fanout-1", "workflow", "parallel", "agent_count", 3)));

        turn.complete("CANCELLED", null);

        assertThat(spans.getFinishedSpanItems()).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("score.ai.tool", "score.ai.workflow", "score.ai.turn");
        assertThat(spans.getFinishedSpanItems()).filteredOn(span ->
                        !span.getName().equals("score.ai.turn"))
                .allSatisfy(span -> assertThat(span.getAttributes().get(
                        AttributeKey.booleanKey("score.ai.observation.incomplete"))).isTrue());
    }

    @Test
    void nestsModelCallsUnderTheActiveAgentRun() {
        ChatRequest request = new ChatRequest("prompt", "request-3", null,
                "conversation-3", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        observe("request-3", AiExecutionEvent.detail(
                "subagent_planned", "", Map.of(
                        "node_id", "worker-1", "workflow", "parallel")));
        AiExecutionObservationExporter exporter = new AiExecutionObservationExporter(observability);
        ExecutionScope scope = new ExecutionScope("request-3", "conversation-3", "user-1",
                1, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        Map<String, Object> attributes = Map.of(
                "agent_run_id", "run-1", "agent_id", "workflow-branch-dynamic-id",
                "model_id", "gpt-5", "workflow_node_id", "worker-1");
        exporter.observe(ExecutionObservation.of("agent.run.started", scope, attributes));
        try (var ignored = observability.makeAgentCurrent("request-3", "run-1")) {
            observability.startModelCall("request-3", "gpt-5", "openai", "assistant")
                    .complete(null);
        }
        exporter.observe(ExecutionObservation.of("agent.run.completed", scope, attributes));
        observe("request-3", AiExecutionEvent.detail(
                "subagent_completed", "", Map.of(
                        "node_id", "worker-1", "workflow", "parallel")));
        turn.complete("COMPLETED", null);

        SpanData workflow = span(spans.getFinishedSpanItems(), "score.ai.workflow");
        SpanData agent = span(spans.getFinishedSpanItems(), "score.ai.agent");
        SpanData model = span(spans.getFinishedSpanItems(), "score.ai.model");
        assertThat(agent.getParentSpanId()).isEqualTo(workflow.getSpanId());
        assertThat(model.getParentSpanId()).isEqualTo(agent.getSpanId());
        assertThat(metrics.collectAllMetrics()).filteredOn(metric ->
                        metric.getName().equals("score.ai.agent.runs"))
                .flatExtracting(metric -> metric.getData().getPoints())
                .allSatisfy(point -> assertThat(point.getAttributes().asMap().keySet())
                        .noneMatch(key -> key.getKey().contains("agent.id")));
    }

    @Test
    void correlatesProviderRetryAttemptsUnderOneModelCallId() {
        ChatRequest request = new ChatRequest("prompt", "request-4", null,
                "conversation-4", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        observability.startModelCall("request-4", "gpt-5", "openai", "assistant")
                .fail(new IllegalStateException("rate limited"));
        observe("request-4", AiExecutionEvent.detail(
                "provider_retry", "private", Map.of(
                        "attempt", 1, "max_attempts", 3, "delay_millis", 1,
                        "status_code", 429, "failure_class", "RateLimitException")));
        observability.startModelCall("request-4", "gpt-5", "openai", "assistant")
                .complete(null);
        turn.complete("COMPLETED", null);

        List<SpanData> modelSpans = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("score.ai.model")).toList();
        assertThat(modelSpans).hasSize(2);
        assertThat(modelSpans).extracting(span -> span.getAttributes().get(
                        AttributeKey.stringKey("score.ai.model_call.id")))
                .containsOnly(modelSpans.getFirst().getAttributes().get(
                        AttributeKey.stringKey("score.ai.model_call.id")));
        assertThat(modelSpans).extracting(span -> span.getAttributes().get(
                        AttributeKey.longKey("score.ai.attempt")))
                .containsExactly(1L, 2L);
    }

    @Test
    void doesNotReportNonStreamingResponseCompletionAsTimeToFirstToken() {
        ChatRequest request = new ChatRequest("prompt", "request-5", null,
                "conversation-5", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);

        observability.startModelCall("request-5", "gpt-5", "openai", "assistant")
                .complete(responseWithUsage());
        turn.complete("COMPLETED", null);

        assertThat(metrics.collectAllMetrics()).extracting(metric -> metric.getName())
                .doesNotContain("score.ai.turn.time_to_first_token");
        assertThat(span(spans.getFinishedSpanItems(), "score.ai.turn").getAttributes().get(
                AttributeKey.doubleKey("score.ai.time_to_first_token_ms"))).isNull();
    }

    @Test
    void doesNotExportExceptionMessagesOrLateLifecycleEvents() {
        String sentinel = "DO_NOT_EXPORT_SECRET_PROMPT_OR_TOOL_RESULT";
        ChatRequest request = new ChatRequest(sentinel, "request-6", null,
                "conversation-6", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        IllegalStateException failure = new IllegalStateException(sentinel);
        observability.startModelCall("request-6", "gpt-5", "openai", "assistant")
                .fail(failure);

        turn.complete("FAILED", failure);
        observe("request-6", AiExecutionEvent.tool(
                "started", sentinel, "late-call", "late-tool", 1));
        AiExecutionObservationExporter exporter = new AiExecutionObservationExporter(observability);
        exporter.observe(ExecutionObservation.of("agent.run.started",
                new ExecutionScope("request-6", "conversation-6", "user-1", 1,
                        ExecutionScope.Purpose.USER_RESPONSE, List.of()),
                Map.of("agent_run_id", "late-run", "agent_id", "late-agent",
                        "model_id", "gpt-5")));

        assertThat(spans.getFinishedSpanItems()).hasSize(2)
                .allSatisfy(span -> {
                    assertThat(span.toString()).doesNotContain(sentinel);
                    assertThat(span.getEvents()).allSatisfy(event ->
                            assertThat(event.toString()).doesNotContain(sentinel));
                });
    }

    @Test
    void ignoresForeignCurrentSpansEvenWhenTheyShareTheInboundTraceId() {
        String traceId = "5bf92f3577b34da6a3ce929d0e0e4736";
        SpanContext remote = SpanContext.createFromRemoteParent(traceId,
                "10f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault());
        Span foreign = tracerProvider.get("global-http").spanBuilder("http.server")
                .setParent(Context.root().with(Span.wrap(remote))).startSpan();
        ChatRequest request = new ChatRequest("prompt", "request-7", null,
                "conversation-7", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1,
                "00-" + traceId + "-10f067aa0ba902b7-01", null);

        try (var ignored = foreign.makeCurrent()) {
            observability.startModelCall("request-7", "gpt-5", "openai", "assistant")
                    .complete(null);
        } finally {
            foreign.end();
        }
        turn.complete("COMPLETED", null);

        SpanData root = span(spans.getFinishedSpanItems(), "score.ai.turn");
        SpanData model = span(spans.getFinishedSpanItems(), "score.ai.model");
        assertThat(model.getParentSpanId()).isEqualTo(root.getSpanId())
                .isNotEqualTo(foreign.getSpanContext().getSpanId());
    }

    @Test
    void marksOnlyMcpToolsAsJsonRpcClientsAndDetectsPartialWorkflowFailure() {
        ChatRequest request = new ChatRequest("prompt", "request-8", null,
                "conversation-8", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        observe("request-8", AiExecutionEvent.tool(
                "started", "", "mcp-call", "find_bies", 1,
                Map.of("mcp", true, "mcp_server_name", "custom-mcp")));
        String currentToolSpanId;
        try (var ignored = observability.makeToolCurrent("request-8", "mcp-call")) {
            currentToolSpanId = Span.current().getSpanContext().getSpanId();
            observe("request-8", AiExecutionEvent.tool(
                    "completed", "", "mcp-call", "find_bies", 1,
                    Map.of("mcp", true, "mcp_server_name", "custom-mcp", "duration_ms", 3L)));
        }
        observe("request-8", AiExecutionEvent.detail(
                "multi_agent_started", "", Map.of(
                        "node_id", "lead-1", "workflow", "parallel", "agent_count", 2)));
        observe("request-8", AiExecutionEvent.detail(
                "multi_agent_completed", "", Map.of(
                        "node_id", "lead-1", "workflow", "parallel", "failed_agents", 1)));
        turn.complete("COMPLETED", null);

        SpanData tool = span(spans.getFinishedSpanItems(), "score.ai.tool");
        SpanData workflow = span(spans.getFinishedSpanItems(), "score.ai.workflow");
        assertThat(tool.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(tool.getSpanId()).isEqualTo(currentToolSpanId);
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("mcp.server.name")))
                .isEqualTo("custom-mcp");
        assertThat(workflow.getAttributes().get(
                AttributeKey.booleanKey("score.ai.workflow.partial_failure"))).isTrue();
        assertThat(workflow.getAttributes().get(AttributeKey.stringKey("score.ai.outcome")))
                .isEqualTo("partial_failure");
    }

    @Test
    void closesAnActiveModelCallBeforeItsTurnAndIgnoresLateCompletion() {
        ChatRequest request = new ChatRequest("prompt", "request-9", null,
                "conversation-9", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        ScoreAiObservability.ModelCall model = observability.startModelCall(
                "request-9", "gpt-5", "openai", "assistant");

        turn.complete("TIMED_OUT", new IllegalStateException("private timeout detail"));
        model.complete(responseWithUsage());

        assertThat(spans.getFinishedSpanItems()).extracting(SpanData::getName)
                .containsExactly("score.ai.model", "score.ai.turn");
        SpanData modelSpan = span(spans.getFinishedSpanItems(), "score.ai.model");
        assertThat(modelSpan.getAttributes().get(
                AttributeKey.booleanKey("score.ai.observation.incomplete"))).isTrue();
        assertThat(modelSpan.getAttributes().get(AttributeKey.stringKey("score.ai.outcome")))
                .isEqualTo("timeout");
    }

    @Test
    void keepsToolScopeLexicalAndBlocksPropagationAfterCrossThreadTurnCleanup() throws Exception {
        ChatRequest request = new ChatRequest("prompt", "request-10", null,
                "conversation-10", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        observe("request-10", AiExecutionEvent.tool(
                "started", "", "tool-call-10", "find_bies", 1,
                Map.of("mcp", true, "mcp_server_name", "configured-mcp")));
        CountDownLatch scopeEntered = new CountDownLatch(1);
        CountDownLatch turnClosed = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                try (var ignored = observability.makeToolCurrent("request-10", "tool-call-10")) {
                    String spanId = Span.current().getSpanContext().getSpanId();
                    scopeEntered.countDown();
                    assertThat(turnClosed.await(5, TimeUnit.SECONDS)).isTrue();
                    return Map.entry(spanId, ScoreAiObservability.currentContextCanPropagate());
                }
            });
            assertThat(scopeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            turn.complete("CANCELLED", null);
            turnClosed.countDown();
            Map.Entry<String, Boolean> observed = result.get(5, TimeUnit.SECONDS);
            SpanData tool = span(spans.getFinishedSpanItems(), "score.ai.tool");
            assertThat(observed.getKey()).isEqualTo(tool.getSpanId());
            assertThat(observed.getValue()).isFalse();
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void recordsConcurrentDuplicateAndLateTerminalsExactlyOnce() throws Exception {
        ChatRequest request = new ChatRequest("prompt", "request-idempotent", null,
                "conversation-idempotent", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        AiExecutionObservationExporter exporter = new AiExecutionObservationExporter(observability);
        ExecutionScope scope = new ExecutionScope(
                "request-idempotent", "conversation-idempotent", "user-1", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        java.util.function.Consumer<AiExecutionEvent> lifecycle = event ->
                exporter.observe(AiExecutionLifecycle.from(event).observation(scope));

        lifecycle.accept(AiExecutionEvent.tool(
                "started", "", "tool-idempotent", "search", 1));
        repeatConcurrently(12, () -> lifecycle.accept(AiExecutionEvent.tool(
                "completed", "", "tool-idempotent", "search", 1,
                Map.of("duration_ms", 3L))));
        lifecycle.accept(AiExecutionEvent.tool(
                "started", "", "tool-idempotent", "search", 1));
        lifecycle.accept(AiExecutionEvent.tool(
                "failed", "", "tool-idempotent", "search", 1));

        lifecycle.accept(AiExecutionEvent.detail(
                "parallel_workflow_started", "", Map.of(
                        "fanout_id", "fanout-idempotent", "workflow", "parallel")));
        repeatConcurrently(12, () -> lifecycle.accept(AiExecutionEvent.detail(
                "parallel_workflow_completed", "", Map.of(
                        "fanout_id", "fanout-idempotent", "workflow", "parallel"))));
        lifecycle.accept(AiExecutionEvent.detail(
                "parallel_workflow_started", "", Map.of(
                        "fanout_id", "fanout-idempotent", "workflow", "parallel")));

        Map<String, Object> agent = Map.of(
                "agent_run_id", "run-idempotent", "agent_id", "worker-1",
                "model_id", "gpt-5", "workflow_node_id", "fanout-idempotent");
        exporter.observe(ExecutionObservation.of("agent.run.started", scope, agent));
        repeatConcurrently(12, () -> exporter.observe(
                ExecutionObservation.of("agent.run.completed", scope, agent)));
        exporter.observe(ExecutionObservation.of("agent.run.started", scope, agent));
        exporter.observe(ExecutionObservation.of("agent.run.failed", scope, agent));

        lifecycle.accept(AiExecutionEvent.detail(
                "mutation_approval_batch_required", "", Map.of("batchId", "batch-idempotent")));
        repeatConcurrently(12, () -> lifecycle.accept(AiExecutionEvent.detail(
                "mutation_approval_decision_accepted", "", Map.of(
                        "batchId", "batch-idempotent", "approved", 1, "denied", 0))));
        lifecycle.accept(AiExecutionEvent.detail(
                "mutation_approval_batch_required", "", Map.of("batchId", "batch-idempotent")));

        lifecycle.accept(AiExecutionEvent.detail(
                "elicitation_required", "", Map.of("elicitationId", "elicitation-idempotent")));
        repeatConcurrently(12, () -> lifecycle.accept(AiExecutionEvent.detail(
                "elicitation_decision_accepted", "",
                Map.of("elicitationId", "elicitation-idempotent"))));
        lifecycle.accept(AiExecutionEvent.detail(
                "elicitation_required", "", Map.of("elicitationId", "elicitation-idempotent")));

        turn.complete("COMPLETED", null);

        assertThat(spans.getFinishedSpanItems()).filteredOn(span ->
                span.getName().equals("score.ai.tool")).hasSize(1);
        assertThat(spans.getFinishedSpanItems()).filteredOn(span ->
                span.getName().equals("score.ai.workflow")).hasSize(1);
        assertThat(spans.getFinishedSpanItems()).filteredOn(span ->
                span.getName().equals("score.ai.agent")).hasSize(1);
        assertThat(longMetric("score.ai.tool.calls")).isEqualTo(1L);
        assertThat(longMetric("score.ai.workflow.runs")).isEqualTo(1L);
        assertThat(longMetric("score.ai.agent.runs")).isEqualTo(1L);
        SpanData root = span(spans.getFinishedSpanItems(), "score.ai.turn");
        assertThat(root.getEvents()).extracting(event -> event.getName())
                .containsOnlyOnce("score.ai.approval.requested")
                .containsOnlyOnce("score.ai.approval.decided")
                .containsOnlyOnce("score.ai.elicitation.requested")
                .containsOnlyOnce("score.ai.elicitation.accepted");
        assertThat(metrics.collectAllMetrics()).filteredOn(metric ->
                        metric.getName().equals("score.ai.approval.wait"))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.getHistogramData().getPoints()).hasSize(2);
                    assertThat(metric.getHistogramData().getPoints())
                            .allSatisfy(point -> assertThat(point.getSum())
                                    .isGreaterThanOrEqualTo(0.0));
                    assertThat(metric.getHistogramData().getPoints())
                            .extracting(point -> point.getAttributes().get(
                                    AttributeKey.stringKey("score.ai.approval.outcome")))
                            .containsExactlyInAnyOrder("approved", "accepted");
                });
    }

    @Test
    void recorderRunsTheToolCallbackUnderTheToolSpanPublishedToObservers() {
        ChatRequest request = new ChatRequest("prompt", "request-recorder-tool", null,
                "conversation-recorder-tool", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        AiExecutionObservationExporter exporter = new AiExecutionObservationExporter(observability);
        ExecutionScope scope = new ExecutionScope(
                "request-recorder-tool", "conversation-recorder-tool", "user-1", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(
                mock(AiChatConversationRepository.class), new ObjectMapper(), mock(ScoreUser.class),
                "conversation-recorder-tool", "request-recorder-tool", "gpt-5", "medium",
                ignored -> { }, null, 0L, Map.of(), scope, exporter, observability);
        AtomicReference<String> callbackSpanId = new AtomicReference<>();
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("search").description("search").inputSchema("{\"type\":\"object\"}").build());
        when(callback.call(anyString(), any(ToolContext.class))).thenAnswer(ignored -> {
            callbackSpanId.set(Span.current().getSpanContext().getSpanId());
            return "{}";
        });

        String result = recorder.recordingTools(() -> new ToolCallback[]{callback})
                .getToolCallbacks()[0].call("{}", new ToolContext(Map.of()));
        turn.complete("COMPLETED", null);

        SpanData tool = span(spans.getFinishedSpanItems(), "score.ai.tool");
        assertThat(result).isEqualTo("{}");
        assertThat(callbackSpanId.get()).isEqualTo(tool.getSpanId());
    }

    @Test
    void tracesAStandaloneAiExecutionWithoutAChatRequest() {
        ScoreAiObservability.Turn turn = observability.startExecution(
                new ScoreAiObservability.ExecutionDescriptor("query-1", "query-1",
                        "gpt-5", "definition_generation", "none"),
                null, 0L, null, null);
        turn.executionStarted();
        observability.startModelCall("query-1", "gpt-5", "openai", "agent")
                .complete(responseWithUsage());
        turn.complete("COMPLETED", null);

        SpanData root = span(spans.getFinishedSpanItems(), "score.ai.turn");
        SpanData model = span(spans.getFinishedSpanItems(), "score.ai.model");
        assertThat(root.getAttributes().get(AttributeKey.stringKey("score.ai.execution.kind")))
                .isEqualTo("definition_generation");
        assertThat(model.getParentSpanId()).isEqualTo(root.getSpanId());
    }

    private ChatResponse responseWithUsage() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(10);
        when(usage.getCompletionTokens()).thenReturn(4);
        when(usage.getCacheReadInputTokens()).thenReturn(3L);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        when(metadata.getId()).thenReturn("provider-response-1");
        when(metadata.getModel()).thenReturn("claude-fable-5-20260701");
        when(metadata.get("cost_usd")).thenReturn(new BigDecimal("0.0012"));
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);
        when(response.getResults()).thenReturn(List.of());
        return response;
    }

    private void observe(String requestId, AiExecutionEvent event) {
        observability.observe(requestId, AiExecutionLifecycle.from(event));
    }

    private void repeatConcurrently(int repetitions, Runnable action) throws Exception {
        CountDownLatch ready = new CountDownLatch(repetitions);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(repetitions)) {
            var futures = IntStream.range(0, repetitions).mapToObj(ignored -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                action.run();
                return null;
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
    }

    private long longMetric(String name) {
        return metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                .mapToLong(LongPointData::getValue).sum();
    }

    private SpanData span(List<SpanData> exported, String name) {
        return exported.stream().filter(item -> item.getName().equals(name)).findFirst().orElseThrow();
    }
}

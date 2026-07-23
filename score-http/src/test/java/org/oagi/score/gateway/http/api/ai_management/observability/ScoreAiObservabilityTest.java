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
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
        model.streaming();
        model.firstChunk();
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
        assertThat(root.getName()).isEqualTo("invoke_agent assistant");
        assertThat(modelSpan.getName()).isEqualTo("chat claude-fable-5");
        assertThat(toolSpan.getName()).isEqualTo("execute_tool get_business_contexts");
        assertThat(root.getAttributes().get(
                AttributeKey.stringKey("gen_ai.conversation.id"))).isEqualTo("conversation-1");
        assertThat(root.getAttributes().get(AttributeKey.stringKey(
                "gen_ai.request.reasoning.level"))).isEqualTo("high");
        assertThat(modelSpan.getAttributes().get(AttributeKey.stringKey(
                "gen_ai.request.reasoning.level"))).isEqualTo("high");
        assertThat(modelSpan.getAttributes().get(AttributeKey.booleanKey(
                "gen_ai.request.stream"))).isTrue();
        assertThat(modelSpan.getAttributes().get(AttributeKey.doubleKey(
                "gen_ai.response.time_to_first_chunk"))).isPositive();
        assertThat(root.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(15L);
        assertThat(root.getAttributes().get(AttributeKey.longKey(
                "gen_ai.usage.cache_read.input_tokens"))).isEqualTo(3L);
        assertThat(root.getAttributes().get(AttributeKey.longKey(
                "gen_ai.usage.cache_creation.input_tokens"))).isEqualTo(2L);
        assertThat(root.getAttributes().get(AttributeKey.stringArrayKey(
                "gen_ai.response.finish_reasons"))).containsExactly("stop");
        assertThat(modelSpan.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(15L);
        assertThat(modelSpan.getAttributes().get(AttributeKey.longKey(
                "gen_ai.usage.cache_read.input_tokens"))).isEqualTo(3L);
        assertThat(modelSpan.getAttributes().get(AttributeKey.longKey(
                "gen_ai.usage.cache_read_tokens"))).isNull();
        assertThat(modelSpan.getAttributes().get(AttributeKey.stringArrayKey(
                "gen_ai.response.finish_reasons"))).containsExactly("stop");
        assertThat(modelSpan.getAttributes().get(AttributeKey.stringKey(
                "gen_ai.response.finish_reason"))).isNull();
        assertThat(exported).allSatisfy(span -> assertThat(span.getAttributes().asMap().keySet())
                .noneMatch(key -> key.getKey().contains("prompt")
                        || key.getKey().contains("content")
                        || key.getKey().contains("result")));

        var exportedMetrics = metrics.collectAllMetrics();
        assertThat(exportedMetrics).extracting(metric -> metric.getName())
                .contains("score.ai.turn.duration", "score.ai.turn.time_to_first_token",
                        "score.ai.model.tokens", "score.ai.tool.duration",
                        "score.ai.guardrail.decisions", "score.ai.cost",
                        "gen_ai.client.operation.duration",
                        "gen_ai.client.operation.time_to_first_chunk",
                        "gen_ai.client.token.usage", "gen_ai.execute_tool.duration",
                        "gen_ai.invoke_agent.duration", "gen_ai.invoke_agent.inference_calls",
                        "gen_ai.invoke_agent.tool_calls");
        assertThat(exportedMetrics).filteredOn(metric -> metric.getName().startsWith("gen_ai."))
                .allSatisfy(metric -> assertThat(metric.getUnit()).isIn(
                        "s", "{token}", "{inference_call}", "{tool_call}"));
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
    void recordsTheExactProviderRequestModelAndPreservesTheRegistryAlias() {
        observability = new ScoreAiObservability(
                OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerProvider)
                        .setMeterProvider(meterProvider)
                        .build(),
                "3.6.0-test",
                alias -> "claude-haiku-4_5".equals(alias)
                        ? "claude-haiku-4-5" : alias);
        ChatRequest request = new ChatRequest("prompt", "request-model-alias", null,
                "conversation-model-alias", null, List.of(), null,
                "claude-haiku-4_5", "high", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(
                request, null, 1, null, null);
        turn.prepared(request);
        observability.startModelCall("request-model-alias", "claude-haiku-4_5",
                        "claude-haiku-4-5", "anthropic", "assistant")
                .complete(null);
        turn.complete("COMPLETED", null);

        SpanData root = span(spans.getFinishedSpanItems(), "score.ai.turn");
        SpanData model = span(spans.getFinishedSpanItems(), "score.ai.model");
        assertThat(root.getAttributes().get(
                AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("claude-haiku-4-5");
        assertThat(model.getName()).isEqualTo("chat claude-haiku-4-5");
        assertThat(model.getAttributes().get(
                AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("claude-haiku-4-5");
        assertThat(model.getAttributes().get(
                AttributeKey.stringKey("score.ai.model.alias")))
                .isEqualTo("claude-haiku-4_5");
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
                .containsExactlyInAnyOrder("execute_tool search",
                        "invoke_workflow parallel",
                        "invoke_agent assistant");
        assertThat(spans.getFinishedSpanItems()).filteredOn(span ->
                        !operation(span).equals("invoke_agent"))
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
                "parallel_workflow_started", "", Map.of(
                        "node_id", "lead-1", "workflow", "parallel")));
        observe("request-3", AiExecutionEvent.detail(
                "parallel_task_planned", "", Map.of(
                        "node_id", "worker-1", "parent_node_id", "lead-1",
                        "workflow", "parallel")));
        AiExecutionObservationExporter exporter = new AiExecutionObservationExporter(observability);
        ExecutionScope scope = new ExecutionScope("request-3", "conversation-3", "user-1",
                1, ExecutionScope.Purpose.USER_RESPONSE, List.of());
        Map<String, Object> attributes = Map.of(
                "agent_run_id", "run-1", "agent_id", "workflow-branch-dynamic-id",
                "model_id", "gpt-5", "workflow_node_id", "worker-1",
                "workflow_parent_node_id", "lead-1");
        exporter.observe(ExecutionObservation.of("agent.run.started", scope, attributes));
        // Simulates a Reactor/thread boundary where Context.current() is lost. With one active
        // agent the parent remains unambiguous and must be restored by the observability facade.
        observability.startModelCall("request-3", "gpt-5", "openai", "assistant")
                .complete(responseWithUsage("tool_use"));
        observe("request-3", AiExecutionEvent.tool(
                "started", "", "agent-tool-1", "search", 1));
        observe("request-3", AiExecutionEvent.tool(
                "completed", "", "agent-tool-1", "search", 1));
        observability.startModelCall("request-3", "gpt-5", "openai", "assistant")
                .complete(responseWithUsage("end_turn"));
        exporter.observe(ExecutionObservation.of("agent.run.completed", scope, attributes));
        observe("request-3", AiExecutionEvent.detail(
                "parallel_task_completed", "", Map.of(
                        "node_id", "worker-1", "parent_node_id", "lead-1",
                        "workflow", "parallel")));
        observe("request-3", AiExecutionEvent.detail(
                "parallel_workflow_completed", "", Map.of(
                        "node_id", "lead-1", "workflow", "parallel")));
        turn.complete("COMPLETED", null);

        SpanData workflow = span(spans.getFinishedSpanItems(), "score.ai.workflow");
        SpanData agent = span(spans.getFinishedSpanItems(), "score.ai.agent");
        SpanData model = span(spans.getFinishedSpanItems(), "score.ai.model");
        SpanData tool = span(spans.getFinishedSpanItems(), "score.ai.tool");
        assertThat(agent.getParentSpanId()).isEqualTo(workflow.getSpanId());
        assertThat(model.getParentSpanId()).isEqualTo(agent.getSpanId());
        assertThat(tool.getParentSpanId()).isEqualTo(agent.getSpanId());
        assertThat(agent.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(20L);
        assertThat(agent.getAttributes().get(AttributeKey.longKey(
                "gen_ai.usage.cache_read.input_tokens"))).isEqualTo(6L);
        assertThat(agent.getAttributes().get(AttributeKey.longKey(
                "gen_ai.usage.cache_creation.input_tokens"))).isEqualTo(4L);
        assertThat(agent.getAttributes().get(AttributeKey.stringArrayKey(
                "gen_ai.response.finish_reasons"))).containsExactly("end_turn");
        assertThat(span(spans.getFinishedSpanItems(), "score.ai.turn").getAttributes().get(
                AttributeKey.longKey("gen_ai.usage.input_tokens"))).isNull();
        assertThat(metrics.collectAllMetrics()).filteredOn(metric ->
                        metric.getName().equals("score.ai.agent.runs"))
                .flatExtracting(metric -> metric.getData().getPoints())
                .allSatisfy(point -> assertThat(point.getAttributes().asMap().keySet())
                        .noneMatch(key -> key.getKey().contains("agent.id")));
    }

    @Test
    void emitsPlannerExecutorAndEvaluatorWithoutInventingSingleAgentWorkflowSpans() {
        ChatRequest request = new ChatRequest("prompt", "request-semantic-workflow", null,
                "conversation-semantic-workflow", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        AiExecutionObservationExporter exporter = new AiExecutionObservationExporter(observability);
        ExecutionScope scope = new ExecutionScope(
                request.requestId(), request.conversationId(), "user-1", 1,
                ExecutionScope.Purpose.WORKFLOW_PLANNING, List.of());
        Map<String, Object> planner = Map.of(
                "agent_run_id", "planner-run", "agent_id", "workflow-planner",
                "model_id", "gpt-5");
        Map<String, Object> assistant = Map.of(
                "agent_run_id", "assistant-run", "agent_id", "connectcenter-assistant",
                "model_id", "gpt-5");
        Map<String, Object> evaluator = Map.of(
                "agent_run_id", "evaluator-run", "agent_id", "workflow-evaluator",
                "model_id", "gpt-5");

        exporter.observe(ExecutionObservation.of("agent.run.started", scope, planner));
        try (var agent = observability.makeAgentCurrent(request.requestId(), "planner-run");
             var plan = observability.startPlan(request.requestId(), "workflow-planner")) {
            observability.startModelCall(
                    request.requestId(), "gpt-5", "openai", "workflow_planning")
                    .complete(responseWithUsage("end_turn"));
        }
        exporter.observe(ExecutionObservation.of("agent.run.completed", scope, planner));
        exporter.observe(ExecutionObservation.of("agent.run.started", scope, assistant));
        exporter.observe(ExecutionObservation.of("agent.run.completed", scope, assistant));
        exporter.observe(ExecutionObservation.of("agent.run.started", scope, evaluator));
        exporter.observe(ExecutionObservation.of("agent.run.completed", scope, evaluator));
        turn.complete("COMPLETED", null);

        List<SpanData> exported = spans.getFinishedSpanItems();
        SpanData root = span(exported, "score.ai.turn");
        SpanData plannerAgent = exported.stream()
                .filter(span -> span.getName().equals("invoke_agent workflow-planner"))
                .findFirst().orElseThrow();
        SpanData plan = exported.stream()
                .filter(span -> span.getName().equals("plan workflow-planner"))
                .findFirst().orElseThrow();
        SpanData model = exported.stream()
                .filter(span -> span.getName().equals("chat gpt-5"))
                .findFirst().orElseThrow();
        SpanData assistantAgent = exported.stream()
                .filter(span -> span.getName().equals("invoke_agent connectcenter-assistant"))
                .findFirst().orElseThrow();
        SpanData evaluatorAgent = exported.stream()
                .filter(span -> span.getName().equals("invoke_agent workflow-evaluator"))
                .findFirst().orElseThrow();

        assertThat(plannerAgent.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(plan.getParentSpanId()).isEqualTo(plannerAgent.getSpanId());
        assertThat(model.getParentSpanId()).isEqualTo(plan.getSpanId());
        assertThat(assistantAgent.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(evaluatorAgent.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(plan.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(plan.getAttributes().get(
                AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("plan");
        assertThat(plannerAgent.getAttributes().get(
                AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(10L);
        assertThat(plannerAgent.getAttributes().get(
                AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(4L);
        assertThat(plannerAgent.getAttributes().get(AttributeKey.stringArrayKey(
                "gen_ai.response.finish_reasons"))).containsExactly("end_turn");
        assertThat(root.getAttributes().get(
                AttributeKey.longKey("gen_ai.usage.input_tokens"))).isNull();
        assertThat(inferenceCallSum("workflow-planner")).isEqualTo(1.0);
        assertThat(inferenceCallSum("assistant")).isZero();
        assertThat(exported).noneMatch(span -> "invoke_workflow".equals(operation(span)));
        assertThat(exported).noneMatch(span -> "create_agent".equals(operation(span)));
    }

    @Test
    void invokingMultipleInProcessAgentsDoesNotPretendToCreateRemoteAgentResources() {
        ChatRequest request = new ChatRequest("prompt", "request-multi-agent", null,
                "conversation-multi-agent", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        AiExecutionObservationExporter exporter = new AiExecutionObservationExporter(observability);
        ExecutionScope scope = new ExecutionScope(
                request.requestId(), request.conversationId(), "user-1", 1,
                ExecutionScope.Purpose.WORKER, List.of());
        Map<String, Object> first = Map.of(
                "agent_run_id", "worker-run-1", "agent_id", "catalog-worker-1",
                "model_id", "gpt-5", "workflow_node_id", "worker-1",
                "workflow_parent_node_id", "lead-1");
        Map<String, Object> second = Map.of(
                "agent_run_id", "worker-run-2", "agent_id", "catalog-worker-2",
                "model_id", "gpt-5", "workflow_node_id", "worker-2",
                "workflow_parent_node_id", "lead-1");

        observe(request.requestId(), AiExecutionEvent.detail(
                "multi_agent_started", "", Map.of(
                        "node_id", "lead-1", "workflow", "orchestrator_workers")));
        observe(request.requestId(), AiExecutionEvent.detail(
                "subagent_started", "", Map.of(
                        "node_id", "worker-1", "parent_node_id", "lead-1",
                        "workflow", "orchestrator_workers")));
        exporter.observe(ExecutionObservation.of("agent.run.started", scope, first));
        exporter.observe(ExecutionObservation.of("agent.run.started", scope, second));
        exporter.observe(ExecutionObservation.of("agent.run.completed", scope, first));
        exporter.observe(ExecutionObservation.of("agent.run.completed", scope, second));
        observe(request.requestId(), AiExecutionEvent.detail(
                "subagent_completed", "", Map.of(
                        "node_id", "worker-1", "parent_node_id", "lead-1",
                        "workflow", "orchestrator_workers")));
        observe(request.requestId(), AiExecutionEvent.detail(
                "multi_agent_completed", "", Map.of(
                        "node_id", "lead-1", "workflow", "orchestrator_workers")));
        turn.complete("COMPLETED", null);

        List<SpanData> exported = spans.getFinishedSpanItems();
        assertThat(exported).filteredOn(span ->
                "invoke_agent".equals(operation(span))).hasSize(3);
        assertThat(exported).filteredOn(span ->
                "invoke_workflow".equals(operation(span))).hasSize(1);
        SpanData workflow = exported.stream()
                .filter(span -> "invoke_workflow".equals(operation(span)))
                .findFirst().orElseThrow();
        assertThat(workflow.getName()).isEqualTo("invoke_workflow orchestrator_workers");
        assertThat(exported).filteredOn(span ->
                "invoke_agent".equals(operation(span))
                        && span.getAttributes().get(
                        AttributeKey.stringKey("score.ai.agent.id")) != null)
                .allSatisfy(span -> assertThat(span.getParentSpanId())
                        .isEqualTo(workflow.getSpanId()));
        assertThat(exported).noneMatch(span ->
                "create_agent".equals(operation(span)));
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
                .filter(span -> operation(span).equals("chat")).toList();
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

        observe("request-5", AiExecutionEvent.detail("context_compacted", "",
                Map.of("reason", "threshold", "automatic", true)));

        observability.startModelCall("request-5", "gpt-5", "openai", "assistant")
                .complete(responseWithUsage());
        turn.complete("COMPLETED", null);

        assertThat(metrics.collectAllMetrics()).extracting(metric -> metric.getName())
                .doesNotContain("score.ai.turn.time_to_first_token");
        assertThat(span(spans.getFinishedSpanItems(), "score.ai.turn").getAttributes().get(
                AttributeKey.doubleKey("score.ai.time_to_first_token_ms"))).isNull();
        assertThat(span(spans.getFinishedSpanItems(), "score.ai.turn").getAttributes().get(
                AttributeKey.booleanKey("gen_ai.conversation.compacted"))).isTrue();
        assertThat(span(spans.getFinishedSpanItems(), "score.ai.model").getAttributes().get(
                AttributeKey.booleanKey("gen_ai.conversation.compacted"))).isTrue();
    }

    @Test
    void distinguishesStreamingDeclarationAndFirstChunkFromFirstToken() {
        ChatRequest request = new ChatRequest("prompt", "request-stream", null,
                "conversation-stream", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        ScoreAiObservability.ModelCall model = observability.startModelCall(
                "request-stream", "gpt-5", "openai", "assistant");

        model.streaming();
        model.firstChunk();
        model.fail(new IllegalStateException("metadata-only stream failed"));
        turn.complete("FAILED", null);

        SpanData modelSpan = span(spans.getFinishedSpanItems(), "score.ai.model");
        assertThat(modelSpan.getAttributes().get(AttributeKey.booleanKey(
                "gen_ai.request.stream"))).isTrue();
        assertThat(modelSpan.getAttributes().get(AttributeKey.doubleKey(
                "gen_ai.response.time_to_first_chunk"))).isPositive();
        assertThat(modelSpan.getAttributes().get(AttributeKey.doubleKey(
                "score.ai.time_to_first_token_ms"))).isNull();
        assertThat(metrics.collectAllMetrics()).extracting(metric -> metric.getName())
                .contains("gen_ai.client.operation.time_to_first_chunk")
                .doesNotContain("score.ai.turn.time_to_first_token");
    }

    @Test
    void marksFailedStreamingRequestBeforeAnyChunkAndNormalizesAzureProvider() {
        ChatRequest request = new ChatRequest("prompt", "request-stream-failure", null,
                "conversation-stream-failure", null, List.of(), null,
                "gpt-5", "medium", "ask");
        ScoreAiObservability.Turn turn = observability.startTurn(request, null, 1, null, null);
        ScoreAiObservability.ModelCall model = observability.startModelCall(
                "request-stream-failure", "gpt-5", "azure-openai", "assistant");

        model.streaming();
        model.fail(new IllegalStateException("failed before first chunk"));
        turn.complete("FAILED", null);

        SpanData modelSpan = span(spans.getFinishedSpanItems(), "score.ai.model");
        assertThat(modelSpan.getAttributes().get(AttributeKey.booleanKey(
                "gen_ai.request.stream"))).isTrue();
        assertThat(modelSpan.getAttributes().get(AttributeKey.doubleKey(
                "gen_ai.response.time_to_first_chunk"))).isNull();
        assertThat(modelSpan.getAttributes().get(AttributeKey.stringKey(
                "gen_ai.provider.name"))).isEqualTo("azure.ai.openai");
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
                Map.of("mcp", true, "mcp_server_name", "custom-mcp",
                        "mcp_protocol_version", "2025-06-18",
                        "network_protocol_name", "http", "network_transport", "tcp",
                        "server_address", "connect-center-mcp", "server_port", 8080)));
        String currentToolSpanId;
        try (var ignored = observability.makeToolCurrent("request-8", "mcp-call")) {
            currentToolSpanId = Span.current().getSpanContext().getSpanId();
            observe("request-8", AiExecutionEvent.tool(
                    "completed", "", "mcp-call", "find_bies", 1,
                    Map.of("mcp", true, "mcp_server_name", "custom-mcp", "duration_ms", 3L,
                            "mcp_protocol_version", "2025-06-18",
                            "network_protocol_name", "http", "network_transport", "tcp",
                            "server_address", "connect-center-mcp", "server_port", 8080)));
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
        assertThat(tool.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(tool.getSpanId()).isEqualTo(currentToolSpanId);
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("score.ai.mcp.server.name")))
                .isEqualTo("custom-mcp");
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("mcp.protocol.version")))
                .isEqualTo("2025-06-18");
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("network.protocol.name")))
                .isEqualTo("http");
        assertThat(tool.getAttributes().get(AttributeKey.longKey("server.port")))
                .isEqualTo(8080L);
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
                .containsExactly("chat gpt-5", "invoke_agent assistant");
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
                operation(span).equals("execute_tool")).hasSize(1);
        assertThat(spans.getFinishedSpanItems()).filteredOn(span ->
                operation(span).equals("invoke_workflow")).hasSize(1);
        assertThat(spans.getFinishedSpanItems()).filteredOn(span ->
                operation(span).equals("invoke_agent")
                        && span.getAttributes().get(AttributeKey.stringKey("score.ai.agent.id")) != null)
                .hasSize(1);
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
        return responseWithUsage("stop");
    }

    private ChatResponse responseWithUsage(String finishReason) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(10);
        when(usage.getCompletionTokens()).thenReturn(4);
        when(usage.getCacheReadInputTokens()).thenReturn(3L);
        when(usage.getCacheWriteInputTokens()).thenReturn(2L);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        when(metadata.getId()).thenReturn("provider-response-1");
        when(metadata.getModel()).thenReturn("claude-fable-5-20260701");
        when(metadata.get("cost_usd")).thenReturn(new BigDecimal("0.0012"));
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);
        ChatGenerationMetadata generationMetadata = mock(ChatGenerationMetadata.class);
        when(generationMetadata.getFinishReason()).thenReturn(finishReason);
        Generation generation = mock(Generation.class);
        when(generation.getMetadata()).thenReturn(generationMetadata);
        when(response.getResults()).thenReturn(List.of(generation));
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

    private double inferenceCallSum(String agentName) {
        return metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(
                        "gen_ai.invoke_agent.inference_calls"))
                .flatMap(metric -> metric.getHistogramData().getPoints().stream())
                .filter(point -> agentName.equals(point.getAttributes().get(
                        AttributeKey.stringKey("gen_ai.agent.name"))))
                .mapToDouble(point -> point.getSum())
                .sum();
    }

    private SpanData span(List<SpanData> exported, String name) {
        String expectedOperation = switch (name) {
            case "score.ai.turn", "score.ai.agent" -> "invoke_agent";
            case "score.ai.model" -> "chat";
            case "score.ai.tool" -> "execute_tool";
            case "score.ai.workflow" -> "invoke_workflow";
            default -> null;
        };
        return exported.stream().filter(item -> expectedOperation != null
                        ? expectedOperation.equals(operation(item))
                        && (!("score.ai.agent".equals(name)) || item.getAttributes().get(
                                AttributeKey.stringKey("score.ai.agent.id")) != null)
                        && (!("score.ai.turn".equals(name)) || item.getAttributes().get(
                                AttributeKey.stringKey("score.ai.agent.id")) == null)
                        : item.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static String operation(SpanData span) {
        return span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"));
    }
}

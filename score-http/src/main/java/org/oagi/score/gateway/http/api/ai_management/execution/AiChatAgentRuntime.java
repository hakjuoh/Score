package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatSession;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiCallReservation;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUsageSettlement;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiUsageAccountingService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.trajectory.ProviderPromptTokenNormalizer;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiChatOptionsFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ProviderCallAccountingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;

/** Owns Agent run lifecycle, observation, MCP admission, and direct Agent model calls. */
final class AiChatAgentRuntime {

    private final ScoreAiModelRegistry models;
    private final ConnectCenterMcpClientFactory mcpClients;
    private final ScoreAiChatOptionsFactory optionsFactory;
    private final SpringAiToolAdapter springAiToolAdapter;
    private final AgentInputGuardrailChain modelInputGuardrails;
    private final AiRequestRegistry requests;
    private final ExecutionObserver observer;
    private final ScoreAiObservability observability;
    private final AiChatConversationRuntime conversations;
    private final org.oagi.score.gateway.http.api.ai_management.provider.AiProviderRetryExecutor
            providerRetry;
    private volatile AiUsageAccountingService accounting;

    AiChatAgentRuntime(ScoreAiModelRegistry models,
                       ConnectCenterMcpClientFactory mcpClients,
                       ScoreAiChatOptionsFactory optionsFactory,
                       SpringAiToolAdapter springAiToolAdapter,
                       AgentInputGuardrailChain modelInputGuardrails,
                       AiRequestRegistry requests, ExecutionObserver observer,
                       ScoreAiObservability observability,
                       AiChatConversationRuntime conversations,
                       org.oagi.score.gateway.http.api.ai_management.provider.AiProviderRetryExecutor
                               providerRetry) {
        this.models = models;
        this.mcpClients = mcpClients;
        this.optionsFactory = optionsFactory;
        this.springAiToolAdapter = springAiToolAdapter;
        this.modelInputGuardrails = modelInputGuardrails;
        this.requests = requests;
        this.observer = observer;
        this.observability = observability;
        this.conversations = conversations;
        this.providerRetry = providerRetry;
    }

    void accounting(AiUsageAccountingService accounting) {
        this.accounting = accounting;
    }

    AgentChatResult executeAgentChat(AgentChatSession session) {
        Objects.requireNonNull(session, "session");
        ChatExecutionContext chatContext = ChatExecutionContext.require(session.context());
        chatContext.recorder().verifyActive();
        var before = chatContext.recorder().usageSnapshot();
        AiChatExecutor.Result result = execute(SpringAiExecutionContextMapper.toProvider(
                        chatContext, session.middlewareState()),
                session.instruction(), session.progress(), session.runControl());
        // Account before any response-side action rejects the result: a provider may finish
        // while cancellation is racing, and those billable tokens must not disappear.
        var usage = usageDelta(before, chatContext.recorder().usageSnapshot());
        return new AgentChatResult(result.answer(), result.traceMetadata(),
                usage != null && usage.modelCalls() > 0
                        ? Optional.of(new AgentRunResult.Usage(
                        usage.promptTokens(), usage.completionTokens(), usage.modelCalls()))
                        : Optional.empty());
    }

    static org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot usageDelta(
            org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot before,
            org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot after) {
        if (after == null) return null;
        long prompt = before != null ? after.promptTokens() - before.promptTokens()
                : after.promptTokens();
        long completion = before != null ? after.completionTokens() - before.completionTokens()
                : after.completionTokens();
        long calls = before != null ? after.modelCalls() - before.modelCalls()
                : after.modelCalls();
        long cached = before != null ? after.cachedTokens() - before.cachedTokens()
                : after.cachedTokens();
        long incomplete = before != null
                ? after.incompleteModelCalls() - before.incompleteModelCalls()
                : after.incompleteModelCalls();
        if (prompt < 0 || completion < 0 || calls < 0 || cached < 0 || incomplete < 0) {
            throw new IllegalStateException("Agent Chat usage counters moved backwards.");
        }
        return new org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot(
                after.nodeId(), after.agentName(), prompt, completion, calls, cached, incomplete);
    }

    AiChatExecutor.Result execute(AiChatExecutor.Context context, Agent.Instruction instruction,
                                  Runnable progress, WorkflowRunControl runControl) {
        context.recorder().verifyActive();
        ExecutionState state = new ExecutionState();
        ExecutionScope scope = AiChatConversationRuntime.executionScope(context);
        String runId = UUID.randomUUID().toString();
        String model = telemetryModel(context.request().modelName());
        observe("agent.run.started", scope, runId, context.agentId(), model,
                observationContext(context), null);
        try (var recorderRun = context.recorder().activateAgentRun(runId);
             var ignored = observability.makeAgentCurrent(scope.requestId(), runId)) {
            try {
                AiChatExecutor.Result identified;
                try (var planning = planningOperation(context, scope)) {
                    try {
                        AiChatExecutor.Result result;
                        if (context.toolPolicy() == AiChatExecutor.ToolPolicy.NONE
                                || context.agentToolBinding() != null) {
                            // Tool-less planner/evaluator calls and Agent-owned bindings do not
                            // consult the MCP registry, avoiding a handshake on every model call.
                            result = conversations.execute(context, null, state, instruction,
                                    progress, runControl);
                        } else {
                            try (ConnectCenterMcpClientFactory.McpSession mcp =
                                         openMcp(context, progress, runControl)) {
                                result = conversations.execute(context, mcp, state, instruction,
                                        progress, runControl);
                            }
                        }
                        identified = result.withExecutionIdentity(context.agentId(),
                                context.request().modelName(), context.executionPurpose().name());
                    } catch (RuntimeException failure) {
                        if (failure instanceof CancellationException) planning.cancel();
                        else planning.fail(failure);
                        throw failure;
                    }
                }
                observe("agent.run.completed", scope, runId, context.agentId(), model,
                        observationContext(context), null);
                return identified;
            } catch (RuntimeException failure) {
                observe(agentFailureEvent(requests, context.request().requestId(), failure),
                        scope, runId, context.agentId(), model,
                        observationContext(context), failure);
                if (failure instanceof AgentGuardrailRefusedException refused) {
                    throw refused.identifiedBy(new Agent.AgentId(context.agentId()));
                }
                throw failure;
            }
        }
    }

    private ConnectCenterMcpClientFactory.McpSession openMcp(
            AiChatExecutor.Context context, Runnable progress, WorkflowRunControl runControl) {
        return conversationsElicitationsAvailable()
                ? mcpClients.open(context.requester(), elicitation ->
                conversations.handleElicitation(context, elicitation, runControl), progress)
                : mcpClients.open(context.requester(), null, progress);
    }

    private boolean conversationsElicitationsAvailable() {
        return conversations.supportsElicitation();
    }

    private ExecutionObservationContext.Operation planningOperation(
            AiChatExecutor.Context context, ExecutionScope scope) {
        return context.executionPurpose() == ExecutionScope.Purpose.WORKFLOW_PLANNING
                ? observability.startPlan(scope.requestId(), context.agentId())
                : ExecutionObservationContext.Operation.noop();
    }

    AgentRunResult executeAgent(AgentInvocation invocation) {
        String runId = UUID.randomUUID().toString();
        String model = telemetryModel(invocation.session().model().id().value());
        String agent = invocation.session().agent().id().value();
        observe("agent.run.started", invocation.scope(), runId, agent, model,
                invocation.observationContext(), null);
        AiTrajectoryRecorder recorder = AgentExecutionRecorderAdapter.providerRecorderOrNull(
                invocation.recorder());
        try (var recorderRun = recorder != null ? recorder.activateAgentRun(runId)
                : AiTrajectoryRecorder.AgentRunActivation.noop();
             var ignored = observability.makeAgentCurrent(invocation.scope().requestId(), runId)) {
            try {
                AgentRunResult result = executeAgentInternal(invocation);
                observe("agent.run.completed", invocation.scope(), runId, agent, model,
                        invocation.observationContext(), null);
                return result;
            } catch (RuntimeException failure) {
                observe(agentFailureEvent(requests, invocation.scope().requestId(), failure),
                        invocation.scope(), runId, agent, model,
                        invocation.observationContext(), failure);
                if (failure instanceof AgentGuardrailRefusedException refused) {
                    throw refused.identifiedBy(invocation.session().agent().id());
                }
                throw failure;
            }
        }
    }

    private AgentRunResult executeAgentInternal(AgentInvocation invocation) {
        String modelId = invocation.session().model().id().value();
        List<AiMessage> assembled = new ArrayList<>();
        assembled.add(new AiMessage.System(invocation.session().instruction().value()));
        assembled.addAll(invocation.history());
        assembled.add(invocation.request());
        AgentInputGuardrailChain.Outcome checked = modelInputGuardrails != null
                ? modelInputGuardrails.evaluate(new AgentInputGuardrail.Request(
                AgentInputGuardrail.Scope.MODEL, invocation.request(), assembled,
                invocation.scope(), Map.of("agent_id", invocation.session().agent().id().value())))
                : AgentInputGuardrailChain.Outcome.allowed(invocation.request(), List.of());
        observability.recordGuardrails(invocation.scope().requestId(), "model_input",
                checked.decisions(), checked.refusal());
        if (!checked.allowed()) throw new AgentInputRefusedException(checked.refusal());

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(invocation.session().instruction().value()));
        invocation.history().stream().map(SpringAiMessageAdapter::toProvider).forEach(messages::add);
        messages.add(SpringAiMessageAdapter.toProvider(checked.input()));
        ScoreAiModelRegistry.ModelConfiguration model = models.modelConfiguration(
                modelId, invocation.scope().requestId());
        String configuredEffort = models.resolveReasoningEffort(
                modelId, invocation.scope().requestId(), null);
        String reasoningEffort = accounting != null
                ? accounting.resolveReasoningEffort(invocation.scope(), modelId, configuredEffort)
                : configuredEffort;
        ChatRequest transport = new ChatRequest(invocation.request().content(),
                invocation.scope().requestId(), invocation.session().agent().id().value(),
                invocation.scope().conversationId(), null, List.of(), null, modelId,
                reasoningEffort, null);
        ChatClient.Builder builder = models.clientBuilder(modelId, invocation.scope().requestId());
        if (accounting != null) {
            builder.defaultAdvisors(new ProviderCallAccountingAdvisor(accounting, transport,
                    invocation.scope(), invocation.session().agent().id().value(),
                    model.providerType()));
        }
        if (!invocation.session().tools().isEmpty()) {
            if (springAiToolAdapter == null) {
                throw new IllegalStateException("The Spring AI Tool adapter is unavailable.");
            }
            builder.defaultTools(springAiToolAdapter.adapt(
                    invocation.session().tools(), invocation.tools(), invocation.scope()));
        }
        AiTrajectoryRecorder recorder = AgentExecutionRecorderAdapter.providerRecorderOrNull(
                invocation.recorder());
        Supplier<ChatResponse> providerCall = () -> modelCall(invocation, recorder, builder,
                modelId, reasoningEffort, model, messages);
        ChatResponse response = providerRetry != null && recorder != null
                ? providerRetry.execute(invocation.scope().requestId(), recorder,
                recorder::executedChangeToolCallCount, providerCall) : providerCall.get();
        String answer = SpringAiResponseContent.visibleStreaming(response);
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("The Agent returned an empty response.");
        }
        AiMessage.Assistant assistant = new AiMessage.Assistant(answer);
        var providerUsage = response.getMetadata().getUsage();
        Optional<AgentRunResult.Usage> usage = providerUsage != null
                ? Optional.of(new AgentRunResult.Usage(nonNegative(providerUsage.getPromptTokens()),
                nonNegative(providerUsage.getCompletionTokens()))) : Optional.empty();
        return new AgentRunResult(assistant, List.of(assistant), usage,
                new AgentRunResult.RunMetadata(invocation.session().agent().id(),
                        invocation.session().model().id(), null,
                        Map.of("guardrail_decisions", checked.decisions().stream()
                                .map(GuardrailDecision::decisionId).toList())));
    }

    private ChatResponse modelCall(AgentInvocation invocation, AiTrajectoryRecorder recorder,
                                   ChatClient.Builder builder, String modelId,
                                   String reasoningEffort,
                                   ScoreAiModelRegistry.ModelConfiguration model,
                                   List<Message> messages) {
        if (recorder != null) recorder.verifyActive();
        var candidate = recorder != null ? recorder.beginModelCall("agent") : null;
        var recording = candidate != null ? candidate
                : AiTrajectoryRecorder.ModelCallRecording.noop();
        ScoreAiObservability.ModelCall observation = observability.startModelCall(
                invocation.scope().requestId(), modelId, model.model(), model.providerType(),
                "agent", recorder != null ? recorder.activeAgentRunId() : null,
                invocation.scope().conversationId(), recording.started());
        try {
            var options = optionsFactory.create(modelId, reasoningEffort, null,
                    invocation.scope().requestId());
            ChatClient.ChatClientRequestSpec prompt = builder.build().prompt()
                    .messages(messages);
            if (options != null) prompt.options(options.mutate());
            ChatResponse response = prompt.call().chatResponse();
            if (recorder != null) observation.eventIdentity(
                    recorder.recordModelResponse(recording, response, false));
            observation.complete(response);
            return response;
        } catch (RuntimeException failure) {
            if (recorder != null) observation.eventIdentity(
                    recorder.failModelCall(recording, failure));
            observation.fail(failure);
            throw failure;
        }
    }

    private void observe(String type, ExecutionScope scope, String runId, String agentId,
                         String modelId, Map<String, Object> context, RuntimeException failure) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("agent_run_id", runId);
        attributes.put("agent_id", agentId);
        attributes.put("model_id", modelId);
        if (context != null) {
            putIfPresent(attributes, "workflow_node_id", context.get("node_id"));
            putIfPresent(attributes, "workflow_parent_node_id", context.get("parent_node_id"));
            putIfPresent(attributes, "workflow_fanout_id", context.get("fanout_id"));
            putIfPresent(attributes, "workflow", context.get("workflow"));
        }
        if (failure != null) attributes.put("failure_type", failure.getClass().getSimpleName());
        observer.observe(ExecutionObservation.of(type, scope, Map.copyOf(attributes)));
    }

    private Map<String, Object> observationContext(AiChatExecutor.Context context) {
        Map<String, Object> attributes = new LinkedHashMap<>(
                context.recorder().observationContext());
        attributes.putAll(context.workflowObservationContext());
        return Map.copyOf(attributes);
    }

    private String telemetryModel(String alias) {
        try {
            var configuration = models.modelConfiguration(alias);
            return configuration != null && StringUtils.hasText(configuration.model())
                    ? configuration.model().strip() : alias;
        } catch (RuntimeException ignored) {
            return alias;
        }
    }

    static String agentFailureEvent(AiRequestRegistry requests, String requestId,
                                    RuntimeException failure) {
        if (requests != null && requests.isTimingOut(requestId)) return "agent.run.timed_out";
        if (failure instanceof CancellationException
                || requests != null && requests.isCancelling(requestId)) {
            return "agent.run.cancelled";
        }
        return "agent.run.failed";
    }

    private static long nonNegative(Number value) {
        return value != null ? Math.max(0L, value.longValue()) : 0L;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}

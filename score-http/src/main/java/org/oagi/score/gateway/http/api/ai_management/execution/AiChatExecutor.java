package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiApprovedExecution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationPermissionMode;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingMutationApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiResolvedMutation;
import org.oagi.score.gateway.http.api.ai_management.provider.AiProviderRetryExecutor;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionState;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentInputRefusedException;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiCallbackToolSetAdapter;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiToolAdapter;
import org.oagi.score.gateway.http.api.ai_management.tool.AiMutationToolGuard;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiUserMessageAdapter;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AiSensitiveDataRedactor;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatSession;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareChain;
import org.oagi.score.gateway.http.api.ai_management.middleware.MiddlewareState;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiChatOptionsFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.TrajectoryRecordingAdvisor;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Executes assistant requests through the configured Spring AI chat model. */
@Component
public final class AiChatExecutor {

    private static final int MAX_READ_BACK_CONTINUATIONS = 2;
    private static final int MAX_TEXTUAL_TOOL_CALL_RECOVERIES = 2;
    private static final ToolCallingAdvisor DIRECT_TOOL_CALLING_ADVISOR =
            ToolCallingAdvisor.builder().build();
    private static final Pattern TEXTUAL_TOOL_CALL_PLACEHOLDER = Pattern.compile(
            "(?is)\\*{0,2}\\[\\s*tool(?:[ -]call)?\\s*:\\s*[^\\]\\r\\n]+]\\*{0,2}"
                    + "(?:\\s*(?:→|->).*?)?\\s*$");
    private final ScoreAiModelRegistry models;
    private final ConnectCenterMcpClientFactory mcpClients;
    private final ToolSearchToolCallingAdvisor toolSearchAdvisor;
    private final AiMutationToolGuard mutationGuard;
    private final AiElicitationService elicitations;
    private final AiProviderRetryExecutor providerRetry;
    private final ScoreAiChatOptionsFactory optionsFactory;
    private final AiMutationApprovalCoordinator approvalCoordinator;
    private final ToolGuardrailRegistry toolGuardrails;
    private final SpringAiCallbackToolSetAdapter callbackToolAdapter;
    private final SpringAiToolAdapter springAiToolAdapter;
    private final AgentInputGuardrailChain modelInputGuardrails;
    private final AiRequestRegistry requests;
    private final AiExecutionInstructions instructions;
    private final ExecutionObserver observer;
    private final ScoreAiObservability observability;
    private final AiMiddlewareChain middleware;

    @Autowired
    public AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                          ToolSearchToolCallingAdvisor toolSearchAdvisor,
                          AiMutationToolGuard mutationGuard,
                          AiElicitationService elicitations,
                          AiProviderRetryExecutor providerRetry,
                          ScoreAiChatOptionsFactory optionsFactory,
                          AiMutationApprovalCoordinator approvalCoordinator,
                          ToolGuardrailRegistry toolGuardrails,
                          SpringAiCallbackToolSetAdapter callbackToolAdapter,
                          SpringAiToolAdapter springAiToolAdapter,
                          AgentInputGuardrailChain modelInputGuardrails,
                          AiRequestRegistry requests,
                          AiExecutionInstructions instructions,
                          ScoreAiObservability observability,
                          AiMiddlewareChain middleware,
                          ObjectProvider<ExecutionObserver> executionObservers) {
        this.models = models;
        this.mcpClients = mcpClients;
        this.toolSearchAdvisor = toolSearchAdvisor;
        this.mutationGuard = mutationGuard;
        this.elicitations = elicitations;
        this.providerRetry = providerRetry;
        this.optionsFactory = optionsFactory;
        this.approvalCoordinator = approvalCoordinator;
        this.toolGuardrails = toolGuardrails;
        this.callbackToolAdapter = callbackToolAdapter;
        this.springAiToolAdapter = springAiToolAdapter;
        this.modelInputGuardrails = modelInputGuardrails;
        this.requests = requests;
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.observability = observability != null ? observability : ScoreAiObservability.noop();
        this.middleware = middleware != null ? middleware : AiMiddlewareChain.none();
        this.observer = ExecutionObserver.composite(executionObservers != null
                ? executionObservers.orderedStream().toList() : List.of());
    }

    /** Compatibility constructor for callers predating configurable middleware. */
    public AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                          ToolSearchToolCallingAdvisor toolSearchAdvisor,
                          AiMutationToolGuard mutationGuard,
                          AiElicitationService elicitations,
                          AiProviderRetryExecutor providerRetry,
                          ScoreAiChatOptionsFactory optionsFactory,
                          AiMutationApprovalCoordinator approvalCoordinator,
                          ToolGuardrailRegistry toolGuardrails,
                          SpringAiCallbackToolSetAdapter callbackToolAdapter,
                          SpringAiToolAdapter springAiToolAdapter,
                          AgentInputGuardrailChain modelInputGuardrails,
                          AiRequestRegistry requests,
                          AiExecutionInstructions instructions,
                          ScoreAiObservability observability,
                          ObjectProvider<ExecutionObserver> executionObservers) {
        this(models, mcpClients, toolSearchAdvisor, mutationGuard, elicitations,
                providerRetry, optionsFactory, approvalCoordinator, toolGuardrails,
                callbackToolAdapter, springAiToolAdapter, modelInputGuardrails, requests,
                instructions, observability, AiMiddlewareChain.none(), executionObservers);
    }

    AiChatExecutor(ScoreAiModelRegistry models,
                   ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   AiMutationToolGuard mutationGuard,
                   AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory,
                   AiMutationApprovalCoordinator approvalCoordinator,
                   ToolGuardrailRegistry toolGuardrails,
                   SpringAiCallbackToolSetAdapter callbackToolAdapter,
                   SpringAiToolAdapter springAiToolAdapter,
                   AgentInputGuardrailChain modelInputGuardrails,
                   AiRequestRegistry requests,
                   ObjectProvider<ExecutionObserver> executionObservers) {
        this(models, mcpClients, toolSearchAdvisor, mutationGuard, elicitations,
                providerRetry, optionsFactory, approvalCoordinator, toolGuardrails,
                callbackToolAdapter, springAiToolAdapter, modelInputGuardrails, requests,
                AiExecutionInstructions.bundled(), ScoreAiObservability.noop(),
                AiMiddlewareChain.none(), executionObservers);
    }

    /** Compatibility constructor for focused executor tests. */
    AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   AiMutationToolGuard mutationGuard,
                   AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory) {
        this(models, mcpClients, toolSearchAdvisor, mutationGuard,
                elicitations, providerRetry, optionsFactory, null, null, null, null, null,
                null, null);
    }

    /** Compatibility constructor for approval-coordination tests. */
    AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   AiMutationToolGuard mutationGuard,
                   AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory,
                   AiMutationApprovalCoordinator approvalCoordinator) {
        this(models, mcpClients, toolSearchAdvisor, mutationGuard,
                elicitations, providerRetry, optionsFactory, approvalCoordinator,
                null, null, null, null, null, null);
    }

    /** Executes a Runner-bound session without selecting or replacing its Agent. */
    public AgentChatResult executeAgentChat(AgentChatSession session) {
        Objects.requireNonNull(session, "session");
        ChatExecutionContext chatContext = ChatExecutionContext.require(session.context());
        chatContext.recorder().verifyActive();
        var before = chatContext.recorder().usageSnapshot();
        Result result = execute(SpringAiExecutionContextMapper.toProvider(
                        chatContext, session.middlewareState()),
                session.instruction(), session.progress());
        // Do not reject the transport result here: AgentRunner records billable usage
        // first and performs the terminal checkpoint before any response-side action.
        // This preserves accounting for a provider that completes during cancellation.
        var usage = usageDelta(before, chatContext.recorder().usageSnapshot());
        return new AgentChatResult(result.answer(), result.traceMetadata(),
                usage != null && usage.modelCalls() > 0
                        ? java.util.Optional.of(new AgentRunResult.Usage(
                        usage.promptTokens(), usage.completionTokens(), usage.modelCalls()))
                        : java.util.Optional.empty());
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
        if (prompt < 0 || completion < 0 || calls < 0) {
            throw new IllegalStateException("Agent Chat usage counters moved backwards.");
        }
        return new org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot(
                after.nodeId(), after.agentName(), prompt, completion, calls);
    }

    /** Package-private provider implementation; the shared Runner supplies the instruction. */
    Result execute(Context context, Agent.Instruction instruction) {
        return execute(context, instruction, () -> { });
    }

    Result execute(Context context, Agent.Instruction instruction,
                   Runnable progress) {
        context.recorder().verifyActive();
        return executeChat(context, Objects.requireNonNull(instruction, "instruction"),
                Objects.requireNonNull(progress, "progress"));
    }

    private Result executeChat(Context context, Agent.Instruction instruction,
                               Runnable progress) {
        ExecutionState state = new ExecutionState();
        ExecutionScope scope = executionScope(context);
        String runId = UUID.randomUUID().toString();
        String telemetryModel = telemetryModel(context.request().modelName());
        observe("agent.run.started", scope, runId, context.agentId(),
                telemetryModel, observationContext(context), null);
        try (var ignored = observability.makeAgentCurrent(scope.requestId(), runId)) {
            try {
                Result identified;
                try (var planning = planningOperation(context, scope)) {
                    try {
                        Result result;
                        if (context.toolPolicy() == ToolPolicy.NONE
                                || context.agentToolBinding() != null) {
                            // Tool-less calls (planner, evaluator, no-tool leaves) never consult the
                            // MCP registry, so they must not pay the per-call MCP handshake.
                            result = execute(context, null, state, instruction, progress);
                        } else {
                            try (ConnectCenterMcpClientFactory.McpSession mcp = elicitations != null
                                    ? mcpClients.open(context.requester(), elicitation -> handleElicitation(
                                    context, context.request(), context.recorder(), elicitation))
                                    : mcpClients.open(context.requester())) {
                                result = execute(context, mcp, state, instruction, progress);
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
                observe("agent.run.completed", scope, runId, context.agentId(),
                        telemetryModel, observationContext(context), null);
                return identified;
            } catch (RuntimeException failure) {
                observe(agentFailureEvent(requests, context.request().requestId(), failure),
                        scope, runId, context.agentId(), telemetryModel,
                        observationContext(context), failure);
                if (failure instanceof AgentGuardrailRefusedException refused) {
                    throw refused.identifiedBy(new Agent.AgentId(context.agentId()));
                }
                throw failure;
            }
        }
    }

    private ExecutionObservationContext.Operation planningOperation(
            Context context, ExecutionScope scope) {
        return context.executionPurpose() == ExecutionScope.Purpose.WORKFLOW_PLANNING
                ? observability.startPlan(scope.requestId(), context.agentId())
                : ExecutionObservationContext.Operation.noop();
    }

    /** Provider-neutral Agent port used by Gateway, Guardrail, and other no-transport runs. */
    AgentRunResult executeAgent(AgentInvocation invocation) {
        String runId = UUID.randomUUID().toString();
        String telemetryModel = telemetryModel(invocation.session().model().id().value());
        observe("agent.run.started", invocation.scope(), runId,
                invocation.session().agent().id().value(),
                telemetryModel, invocation.observationContext(), null);
        try (var ignored = observability.makeAgentCurrent(invocation.scope().requestId(), runId)) {
            try {
                AgentRunResult result = executeAgentInternal(invocation);
                observe("agent.run.completed", invocation.scope(), runId,
                        invocation.session().agent().id().value(),
                        telemetryModel, invocation.observationContext(), null);
                return result;
            } catch (RuntimeException failure) {
                observe(agentFailureEvent(requests, invocation.scope().requestId(), failure),
                        invocation.scope(), runId, invocation.session().agent().id().value(),
                        telemetryModel, invocation.observationContext(), failure);
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
        ChatClient.Builder builder = models.clientBuilder(modelId);
        if (!invocation.session().tools().isEmpty()) {
            if (springAiToolAdapter == null) {
                throw new IllegalStateException("The Spring AI Tool adapter is unavailable.");
            }
            builder.defaultTools(springAiToolAdapter.adapt(
                    invocation.session().tools(), invocation.tools(), invocation.scope()));
        }
        String reasoningEffort = models.resolveReasoningEffort(modelId, null);
        ScoreAiModelRegistry.ModelConfiguration model = models.modelConfiguration(modelId);
        ScoreAiObservability.ModelCall modelCall = observability.startModelCall(
                invocation.scope().requestId(), modelId, model.model(),
                model.providerType(), "agent");
        ChatResponse response;
        try {
            response = builder.build().prompt()
                    .options(optionsFactory.create(modelId, reasoningEffort, null).mutate())
                    .messages(messages).call().chatResponse();
            modelCall.complete(response);
        } catch (RuntimeException failure) {
            modelCall.fail(failure);
            throw failure;
        }
        String answer = visibleContent(response);
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("The Agent returned an empty response.");
        }
        AiMessage.Assistant assistant = new AiMessage.Assistant(answer);
        org.springframework.ai.chat.metadata.Usage providerUsage =
                response.getMetadata().getUsage();
        Optional<AgentRunResult.Usage> usage = providerUsage != null
                ? Optional.of(new AgentRunResult.Usage(
                nonNegative(providerUsage.getPromptTokens()),
                nonNegative(providerUsage.getCompletionTokens())))
                : Optional.empty();
        return new AgentRunResult(assistant, List.of(assistant), usage,
                new AgentRunResult.RunMetadata(invocation.session().agent().id(),
                        invocation.session().model().id(), null,
                        Map.of("guardrail_decisions", checked.decisions().stream()
                                .map(GuardrailDecision::decisionId).toList())));
    }

    private long nonNegative(Number value) {
        return value != null ? Math.max(0L, value.longValue()) : 0L;
    }

    private void observe(String type, ExecutionScope scope, String runId, String agentId,
                         String modelId, Map<String, Object> lifecycleContext,
                         RuntimeException failure) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("agent_run_id", runId);
        attributes.put("agent_id", agentId);
        attributes.put("model_id", modelId);
        if (lifecycleContext != null) {
            putIfPresent(attributes, "workflow_node_id", lifecycleContext.get("node_id"));
            putIfPresent(attributes, "workflow_parent_node_id",
                    lifecycleContext.get("parent_node_id"));
            putIfPresent(attributes, "workflow_fanout_id", lifecycleContext.get("fanout_id"));
            putIfPresent(attributes, "workflow", lifecycleContext.get("workflow"));
        }
        if (failure != null) {
            attributes.put("failure_type", failure.getClass().getSimpleName());
        }
        observer.observe(ExecutionObservation.of(type, scope, Map.copyOf(attributes)));
    }

    private Map<String, Object> observationContext(Context context) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(
                context.recorder().observationContext());
        attributes.putAll(context.workflowObservationContext());
        return Map.copyOf(attributes);
    }

    private String telemetryModel(String modelAlias) {
        try {
            ScoreAiModelRegistry.ModelConfiguration configuration =
                    models.modelConfiguration(modelAlias);
            return configuration != null && StringUtils.hasText(configuration.model())
                    ? configuration.model().strip() : modelAlias;
        } catch (RuntimeException ignored) {
            return modelAlias;
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
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

    /**
     * Installs the concrete Tool binding selected by the Agent definition.
     * A non-null binding explicitly overrides the transport/MCP registry; an
     * empty binding therefore intentionally exposes no callbacks.
     */
    private void configureBoundTools(Context context, ChatClient.Builder assistantBuilder,
                                     AiTrajectoryRecorder recorder, long toolOutputTokenLimit) {
        AgentToolBinding binding = Objects.requireNonNull(context.agentToolBinding(),
                "Agent Tool binding");
        if (binding.tools().isEmpty()) return;
        if (!binding.gateway().enabled()) {
            throw new IllegalStateException("An Agent Tool binding requires an enabled gateway.");
        }
        ExecutionScope scope = executionScope(context);
        assistantBuilder.defaultTools(recorder.recordingTools(
                springAiToolAdapter.adapt(binding.tools(), binding.gateway(), scope),
                toolOutputTokenLimit));
    }

    private Result execute(Context context, ConnectCenterMcpClientFactory.McpSession mcp,
                           ExecutionState executionState, Agent.Instruction instruction,
                           Runnable progress) {
        var request = context.request();
        AiTrajectoryRecorder recorder = context.recorder();
        ChatOptions options = optionsFactory.create(
                request.modelName(), request.reasoningEffort(), request.routeManifest());
        ScoreAiModelRegistry.ModelConfiguration model = models.modelConfiguration(request.modelName());
        recorder.useModelProvider(model.providerType(), model.model());
        if (mcp != null && mcp.tools() != null) {
            recorder.mcpToolNames(java.util.Arrays.stream(mcp.tools().getToolCallbacks())
                    .map(callback -> callback.getToolDefinition().name()).toList());
            var telemetry = mcp.telemetry();
            recorder.mcpTelemetry(telemetry.serverName(), telemetry.protocolVersion(),
                    telemetry.serverAddress(), telemetry.serverPort(),
                    telemetry.networkProtocolName(), telemetry.networkTransport());
        } else {
            recorder.mcpToolNames(List.of());
        }
        long toolOutputTokenLimit = model.contextBudget() != null
                && model.contextBudget().toolOutputTokenLimit() != null
                ? model.contextBudget().toolOutputTokenLimit() : Long.MAX_VALUE;
        {
            ChatClient.Builder assistantBuilder = models.clientBuilder(request.modelName())
                    .defaultAdvisors(new TrajectoryRecordingAdvisor(recorder, observability));
            AiMutationToolGuard.GuardedToolSession guardedSession = null;
            org.springframework.ai.tool.ToolCallbackProvider executableTools = null;
            if (context.agentToolBinding() != null) {
                configureBoundTools(context, assistantBuilder, recorder, toolOutputTokenLimit);
            } else if (mcp != null && mcp.client() != null
                    && context.toolPolicy() != ToolPolicy.NONE) {
                recorder.readOnlyToolNames(mcp.readOnlyToolNames());
                boolean commonToolGateway = toolGuardrails != null
                        && callbackToolAdapter != null && springAiToolAdapter != null;
                boolean hasTools = mcp.tools() != null
                        && mcp.tools().getToolCallbacks().length > 0;
                if (hasTools && !commonToolGateway) {
                    throw new IllegalStateException(
                            "The mandatory Tool execution gateway is not configured.");
                }
                if (context.toolPolicy() == ToolPolicy.FULL) {
                    guardedSession = mutationGuard != null
                            ? commonToolGateway
                            ? mutationGuard.authorizationSession(request, context.requester(),
                                    approvalCoordinator != null
                                            ? ignored -> { }
                                            : recorder::mutationConfirmationRequired,
                                    mcp.tools(), mcp.readOnlyToolNames())
                            : mutationGuard.session(request, context.requester(),
                                    approvalCoordinator != null
                                            ? ignored -> { }
                                            : recorder::mutationConfirmationRequired,
                                    mcp.tools(), mcp.readOnlyToolNames())
                            : null;
                }
                var guardedTools = context.toolPolicy() == ToolPolicy.READ_ONLY
                        ? mutationGuard != null
                                ? mutationGuard.readOnly(mcp.tools(), mcp.readOnlyToolNames())
                                : (org.springframework.ai.tool.ToolCallbackProvider) () ->
                                        new org.springframework.ai.tool.ToolCallback[0]
                        : guardedSession != null ? guardedSession : mcp.tools();
                if (hasTools) {
                    var coreTools = callbackToolAdapter.adapt(guardedTools, mcp.readOnlyToolNames());
                    ExecutionScope scope = executionScope(context);
                    long rawByteLimit = toolOutputTokenLimit == Long.MAX_VALUE
                            ? 16L * 1024L * 1024L
                            : Math.max(4096L, Math.min(16L * 1024L * 1024L,
                            toolOutputTokenLimit * 4L));
                    ToolExecutionGateway.RequestFence requestFence =
                            new ToolExecutionGateway.RequestFence() {
                            @Override
                            public void verifyActive(ExecutionScope scopeToCheck) {
                                recorder.verifyActive();
                                if (Thread.currentThread().isInterrupted()
                                        || requests != null
                                        && requests.shouldDiscardResult(scopeToCheck.requestId())) {
                                    throw new CancellationException(
                                            "The assistant request stopped before Tool execution.");
                                }
                            }

                            @Override
                            public <T> T callIfActive(ExecutionScope scopeToCheck,
                                                      java.util.function.Supplier<T> action) {
                                if (Thread.currentThread().isInterrupted()) {
                                    throw new CancellationException(
                                            "The assistant request stopped before Tool execution.");
                                }
                                progress.run();
                                if (requests != null) {
                                    requests.admitToolExecution(scopeToCheck.requestId());
                                } else {
                                    recorder.verifyActive();
                                }
                                try {
                                    return Objects.requireNonNull(action, "action").get();
                                } finally {
                                    progress.run();
                                }
                            }
                    };
                    ToolExecutionGateway gateway = new ToolExecutionGateway(coreTools, toolGuardrails,
                            guardedSession != null ? List.of(guardedSession) : List.of(),
                            requestFence,
                            observer, executionState, rawByteLimit,
                            middleware, context.middlewareState());
                    // Recording is deliberately outside the gateway: trajectory and UI
                    // observers may see only the bounded, output-guarded Tool result.
                    executableTools = recorder.recordingTools(
                            springAiToolAdapter.adapt(coreTools, gateway, scope),
                            toolOutputTokenLimit);
                } else {
                    executableTools = recorder.recordingTools(guardedTools, toolOutputTokenLimit);
                }
                assistantBuilder.defaultTools(executableTools);
                // A confirmed continuation already has a server-bound target tool.
                // Give the model the guarded callbacks directly so it can resume that
                // invocation and read it back without rediscovering it through
                // toolSearchTool. Other mutations remain protected by the guard.
                if (request.mutationConfirmation() != null) {
                    assistantBuilder.defaultAdvisors(DIRECT_TOOL_CALLING_ADVISOR);
                } else {
                    // READ_ONLY sessions have already been reduced to the server-declared
                    // read-only callback set above. Keep that private safe registry deferred too,
                    // so delegated workers do not pay the context cost of every read schema.
                    assistantBuilder.defaultAdvisors(toolSearchAdvisor);
                }
            }
            List<Message> messages = new ArrayList<>(context.history());
            if (request.mutationConfirmation() != null
                    && request.mutationConfirmation().revised()) {
                messages.add(new SystemMessage(instructions.render(
                        AiExecutionInstructions.Template.REVISED_MUTATION_CONTINUATION,
                        Map.of("toolName", request.mutationConfirmation().toolName())).value()));
            }
            messages.add(context.userMessage());
            if (guardedSession != null && executableTools != null) {
                guardedSession.executeApproved(executableTools)
                        .ifPresent(execution -> addApprovedExecution(
                                messages, execution, recorder, toolOutputTokenLimit));
            }
            ChatClient assistant = assistantBuilder.build();
            boolean internalPersona = internalPersona(context);
            long completedToolCallsBeforeAnswer = recorder.completedToolCallCount();
            String answer = invoke(assistant, options, request, messages, recorder,
                    context.streamVisibleContent(), internalPersona, executionScope(context),
                    executionState, instruction, progress);
            int textualToolCallRecovery = 0;
            while (isTextualToolCallPlaceholder(answer)
                    && recorder.completedToolCallCount() == completedToolCallsBeforeAnswer
                    && textualToolCallRecovery++ < MAX_TEXTUAL_TOOL_CALL_RECOVERIES) {
                List<Message> recoveryMessages = new ArrayList<>(messages);
                recoveryMessages.add(new AssistantMessage(answer));
                recoveryMessages.add(new UserMessage(instructions.render(
                        AiExecutionInstructions.Template.TEXTUAL_TOOL_CALL_RECOVERY).value()));
                answer = invoke(assistant, options, request, recoveryMessages, recorder,
                        context.streamVisibleContent(), internalPersona, executionScope(context),
                        executionState, instruction, progress);
            }
            if (isTextualToolCallPlaceholder(answer)) {
                throw new IllegalStateException(
                        "The assistant repeatedly returned a textual tool-call placeholder.");
            }
            List<Message> approvalMessages = new ArrayList<>(messages);
            int approvalBarrierCount = 0;
            int approvedMutationCount = 0;
            int deniedMutationCount = 0;
            int failedMutationCount = 0;
            while (guardedSession != null && approvalCoordinator != null
                    && !guardedSession.pendingApprovals().isEmpty()) {
                List<AiPendingMutationApproval> pendingApprovals =
                        List.copyOf(guardedSession.pendingApprovals());
                AiMutationApprovalScope approvalScope = context.approvalScope() != null
                        ? context.approvalScope()
                        : AiMutationApprovalScope.root(request.conversationId());
                Map<String, AiMutationApprovalResolution> decisions;
                List<AiResolvedMutation> resolutions;
                try {
                    context.approvalWaitLifecycle().suspendForApproval();
                    try {
                        decisions = approvalCoordinator.awaitDecisions(
                                context.requester(), request.requestId(), request.conversationId(),
                                approvalScope, pendingApprovals,
                                recorder::mutationApprovalBatchRequired,
                                recorder::mutationApprovalDecisionAccepted);
                    } finally {
                        context.approvalWaitLifecycle().resumeAfterApproval();
                    }
                    resolutions = guardedSession.resolveApprovals(executableTools, decisions);
                } finally {
                    recorder.mutationApprovalsResolved(pendingApprovals);
                }
                approvalBarrierCount++;
                int executed = (int) resolutions.stream()
                        .filter(AiResolvedMutation::executed).count();
                // An approved call that then failed is not a denial: the user did approve it.
                int denied = (int) resolutions.stream()
                        .filter(resolution -> !resolution.executed())
                        .filter(resolution -> resolution.result() != null
                                && resolution.result().contains(
                                        AiMutationToolGuard.MUTATION_CONFIRMATION_DENIED))
                        .count();
                approvedMutationCount += executed;
                deniedMutationCount += denied;
                failedMutationCount += resolutions.size() - executed - denied;
                approvalMessages.add(new AssistantMessage(answer));
                resolutions.forEach(resolution -> addResolvedMutation(
                        approvalMessages, resolution, recorder, toolOutputTokenLimit));
                approvalMessages.add(new UserMessage(instructions.render(
                        AiExecutionInstructions.Template.APPROVAL_CONTINUATION).value()));
                answer = invoke(assistant, options, request, approvalMessages, recorder,
                        false, internalPersona, executionScope(context), executionState,
                        instruction, progress);
            }
            int continuation = 0;
            while (guardedSession != null && guardedSession.mutationCompleted()
                    && !guardedSession.confirmationRequired()
                    && !guardedSession.readAfterLastMutation()
                    && continuation++ < MAX_READ_BACK_CONTINUATIONS) {
                List<Message> continuationMessages = new ArrayList<>(context.history());
                continuationMessages.add(context.userMessage());
                guardedSession.completedMutations()
                        .forEach(execution -> addApprovedExecution(
                                continuationMessages, execution, recorder, toolOutputTokenLimit));
                continuationMessages.add(new AssistantMessage(answer));
                continuationMessages.add(new UserMessage(instructions.render(
                        AiExecutionInstructions.Template.READ_BACK_CONTINUATION).value()));
                answer = invoke(assistant, options, request, continuationMessages, recorder,
                        false, internalPersona, executionScope(context), executionState,
                        instruction, progress);
            }
            if (guardedSession != null && guardedSession.mutationCompleted()
                    && !guardedSession.confirmationRequired()
                    && !guardedSession.readAfterLastMutation()) {
                throw new IllegalStateException(
                        "The assistant stopped after a mutation without completing read-back.");
            }
            if (approvalBarrierCount > 0) {
                return new Result(answer, Map.of(
                        "approvalBarrierResolved", true,
                        "approvalBarrierCount", approvalBarrierCount,
                        "approvedMutationCount", approvedMutationCount,
                        "deniedMutationCount", deniedMutationCount,
                        "failedMutationCount", failedMutationCount));
            }
            return new Result(answer);
        }
    }

    /** Internal execution purposes own their instructions and never inherit ROOT. */
    private boolean internalPersona(Context context) {
        return context.executionPurpose() != ExecutionScope.Purpose.USER_RESPONSE;
    }

    private ExecutionScope executionScope(Context context) {
        String requesterId = context.requester() != null && context.requester().userId() != null
                ? context.requester().userId().value().toString()
                : context.requester() != null && StringUtils.hasText(context.requester().username())
                ? context.requester().username() : "unknown";
        return new ExecutionScope(context.request().requestId(), context.request().conversationId(),
                requesterId, context.agentDepth(), context.executionPurpose(),
                context.guardrailDecisionIds());
    }

    private McpSchema.ElicitResult handleElicitation(
            Context context, ChatRequest request, AiTrajectoryRecorder recorder,
            McpSchema.ElicitFormRequest elicitation) {
        if (AiMutationPermissionMode.resolve(request.permissionMode())
                == AiMutationPermissionMode.FULL_ACCESS
                && isConfirmationOnly(elicitation.requestedSchema())) {
            return new McpSchema.ElicitResult(
                    McpSchema.ElicitResult.Action.ACCEPT, java.util.Map.of());
        }
        return elicitations.await(context.requester(), request.conversationId(), request.requestId(),
                elicitation, recorder::elicitationRequired);
    }

    private boolean isConfirmationOnly(java.util.Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof java.util.Map<?, ?> properties)
                || !properties.isEmpty()) {
            return false;
        }
        return !(schema.get("required") instanceof java.util.Collection<?> required)
                || required.isEmpty();
    }

    private String invoke(ChatClient assistant, ChatOptions options,
                          ChatRequest request,
                          List<Message> messages, AiTrajectoryRecorder recorder,
                          boolean streamVisibleContent, boolean internalPersona,
                          ExecutionScope scope, ExecutionState executionState,
                          Agent.Instruction instruction, Runnable progress) {
        if (providerRetry == null) {
            return attemptInvoke(assistant, options, request, messages, recorder,
                    streamVisibleContent, internalPersona, scope, instruction, progress);
        }
        // Transient provider failures are retried with visible backoff. An attempt
        // that executed a data-changing tool is terminal: the recorder's mutation
        // count is the executor's replay fence.
        return providerRetry.execute(request, recorder,
                () -> Math.max(executionState.completedMutations(),
                        recorder.executedMutationToolCallCount()), executionState,
                () -> attemptInvoke(assistant, options, request, messages, recorder,
                        streamVisibleContent, internalPersona, scope, instruction, progress));
    }

    private String attemptInvoke(ChatClient assistant, ChatOptions options,
                                 ChatRequest request,
                                 List<Message> messages, AiTrajectoryRecorder recorder,
                                 boolean streamVisibleContent, boolean internalPersona,
                                 ExecutionScope scope, Agent.Instruction instruction,
                                 Runnable progress) {
        recorder.verifyActive();
        if (Thread.currentThread().isInterrupted()
                || requests != null && requests.shouldDiscardResult(scope.requestId())) {
            throw new CancellationException(
                    "The assistant request stopped before model execution.");
        }
        progress.run();
        // Visible text emitted before a tool call is interim narration, not the
        // answer. Tool completions mark segment boundaries; the answer restarts
        // at the first substantive chunk after a boundary so it reflects the
        // tool results it follows. A boundary followed only by whitespace never
        // resets, so such a stream still returns the accumulated earlier text.
        // The UI splits its streamed bubbles at the same boundaries.
        StringBuilder answer = new StringBuilder();
        long[] toolBoundary = {recorder.completedToolCallCount()};
        // Planner, evaluator, and worker calls supply their own leading system
        // prompt; sending the assistant persona and page context to them wastes
        // input tokens on every request.
        String stableSystemPrompt = instruction.value();
        List<Message> requestMessages = new ArrayList<>(messages.size() + 1);
        requestMessages.addAll(messages);
        int guardedUserIndex = lastUserMessageIndex(requestMessages);
        // Keep volatile page data out of every system block. Appending it as
        // untrusted turn context preserves the stable system-prompt prefix for
        // provider caching, matching Claude Code's user-context path.
        if (!internalPersona) {
            requestMessages.add(new UserMessage(AiSensitiveDataRedactor.redactText(
                    requestScopedInput(request, instructions))));
        }
        requestMessages = guardModelInput(request, requestMessages, guardedUserIndex, scope);
        ChatClient.ChatClientRequestSpec prompt = assistant.prompt()
                    .options(options.mutate());
        prompt = prompt.system(system -> system.text(stableSystemPrompt));
        prompt
                    .messages(requestMessages)
                    .advisors(advisor -> advisor
                            .param(ChatMemory.CONVERSATION_ID, request.conversationId())
                            .param(AiTrajectoryRecorder.PHASE_CONTEXT_KEY, "assistant"))
                    .stream()
                    .chatResponse()
                    // Raw provider chunks are activity even when they contain only
                    // hidden reasoning or Tool-call protocol data.
                    .doOnNext(ignored -> progress.run())
                    .map(this::visibleContent)
                    .filter(content -> !content.isEmpty())
                    .doOnNext(content -> {
                        long boundary = recorder.completedToolCallCount();
                        if (boundary != toolBoundary[0] && StringUtils.hasText(content)) {
                            toolBoundary[0] = boundary;
                            answer.setLength(0);
                        }
                        answer.append(content);
                        // Candidate tokens remain private until the application-level
                        // Agent Output Guardrail chain accepts or rewrites the complete
                        // response. ChatService publishes only that safe result.
                    })
                    .then()
                    .block();
        if (answer.isEmpty() || !StringUtils.hasText(answer.toString())) {
            throw new IllegalStateException("The assistant returned an empty response.");
        }
        return answer.toString();
    }

    private List<Message> guardModelInput(ChatRequest request, List<Message> messages,
                                          int guardedUserIndex, ExecutionScope scope) {
        if (modelInputGuardrails == null) return messages;
        List<AiMessage> assembled = messages.stream().map(SpringAiMessageAdapter::toCore).toList();
        AiMessage.User input = guardedUserIndex >= 0
                ? (AiMessage.User) assembled.get(guardedUserIndex) : new AiMessage.User("");
        AgentInputGuardrailChain.Outcome outcome = modelInputGuardrails.evaluate(
                new AgentInputGuardrail.Request(AgentInputGuardrail.Scope.MODEL,
                        input, assembled, scope, Map.of("model", request.modelName())));
        observability.recordGuardrails(scope.requestId(), "model_input",
                outcome.decisions(), outcome.refusal());
        if (!outcome.allowed()) {
            throw new AgentInputRefusedException(outcome.refusal());
        }
        if (guardedUserIndex < 0) return messages;
        List<Message> rewritten = new ArrayList<>(messages);
        rewritten.set(guardedUserIndex, SpringAiUserMessageAdapter.toSpring(outcome.input()));
        return List.copyOf(rewritten);
    }

    private int lastUserMessageIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) return index;
        }
        return -1;
    }

    private boolean isTextualToolCallPlaceholder(String answer) {
        return StringUtils.hasText(answer)
                && TEXTUAL_TOOL_CALL_PLACEHOLDER.matcher(answer).find();
    }

    static String requestScopedInput(ChatRequest request) {
        return requestScopedInput(request, AiExecutionInstructions.bundled());
    }

    private static String requestScopedInput(ChatRequest request,
                                             AiExecutionInstructions instructions) {
        String pageContext = request != null && StringUtils.hasText(request.pageContext())
                ? request.pageContext() : "Not provided";
        return instructions.render(AiExecutionInstructions.Template.REQUEST_SCOPED_INPUT,
                Map.of("pageContext", pageContext)).value();
    }

    private void addApprovedExecution(List<Message> messages,
                                      AiApprovedExecution execution,
                                      AiTrajectoryRecorder recorder, long toolOutputTokenLimit) {
        String callId = "approved-" + UUID.randomUUID();
        messages.add(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(callId, "function", execution.toolName(),
                        execution.arguments()))).build());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, execution.toolName(),
                        recorder.limitToolOutput(execution.result(), toolOutputTokenLimit,
                                execution.toolName())))).build());
    }

    private void addResolvedMutation(List<Message> messages,
                                     AiResolvedMutation resolution,
                                     AiTrajectoryRecorder recorder,
                                     long toolOutputTokenLimit) {
        String callId = "approved-" + UUID.randomUUID();
        messages.add(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(callId, "function", resolution.toolName(),
                        resolution.arguments()))).build());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, resolution.toolName(),
                        recorder.limitToolOutput(resolution.result(), toolOutputTokenLimit,
                                resolution.toolName())))).build());
    }

    private String visibleContent(ChatResponse response) {
        if (response == null) {
            return "";
        }
        return response.getResults().stream()
                .map(generation -> generation.getOutput())
                .filter(output -> !isReasoning(output))
                .map(AssistantMessage::getText)
                .filter(text -> text != null && !text.isEmpty())
                .reduce("", String::concat);
    }

    private boolean isReasoning(AssistantMessage output) {
        return output.getMetadata().containsKey("signature")
                || output.getMetadata().containsKey("data")
                || Boolean.TRUE.equals(output.getMetadata().get("thinking"));
    }

    record Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                          ScoreUser requester, AiTrajectoryRecorder recorder,
                          boolean toolsEnabled, boolean streamVisibleContent,
                          ToolPolicy toolPolicy, int agentDepth,
                          AiMutationApprovalScope approvalScope,
                          AgentApprovalWaitLifecycle approvalWaitLifecycle,
                          String agentId,
                          org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose executionPurpose,
                          List<String> guardrailDecisionIds,
                          Map<String, Object> workflowObservationContext,
                          AgentToolBinding agentToolBinding,
                          MiddlewareState middlewareState) {

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiMutationApprovalScope approvalScope,
                       AgentApprovalWaitLifecycle approvalWaitLifecycle,
                       String agentId,
                       org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose executionPurpose,
                       List<String> guardrailDecisionIds,
                       Map<String, Object> workflowObservationContext,
                       AgentToolBinding agentToolBinding) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                    approvalWaitLifecycle, agentId, executionPurpose, guardrailDecisionIds,
                    workflowObservationContext, agentToolBinding, new MiddlewareState());
        }

        /** Existing transport callers do not provide an Agent-owned binding. */
        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiMutationApprovalScope approvalScope,
                       AgentApprovalWaitLifecycle approvalWaitLifecycle,
                       String agentId,
                       org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose executionPurpose,
                       List<String> guardrailDecisionIds,
                       Map<String, Object> workflowObservationContext) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                    approvalWaitLifecycle, agentId, executionPurpose, guardrailDecisionIds,
                    workflowObservationContext, null);
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiMutationApprovalScope approvalScope,
                       AgentApprovalWaitLifecycle approvalWaitLifecycle,
                       String agentId,
                       org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose executionPurpose,
                       List<String> guardrailDecisionIds) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                    approvalWaitLifecycle, agentId, executionPurpose, guardrailDecisionIds,
                    Map.of());
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiMutationApprovalScope approvalScope,
                       AgentApprovalWaitLifecycle approvalWaitLifecycle,
                       String agentId,
                       org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose executionPurpose) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                    approvalWaitLifecycle, agentId, executionPurpose, List.of());
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiMutationApprovalScope approvalScope,
                       AgentApprovalWaitLifecycle approvalWaitLifecycle) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                    approvalWaitLifecycle, defaultAgentId(toolPolicy, agentDepth),
                    defaultPurpose(toolPolicy, agentDepth));
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder) {
            this(request, history, userMessage, requester, recorder,
                    true, true, ToolPolicy.FULL, 0,
                    request != null && request.conversationId() != null
                            ? AiMutationApprovalScope.root(request.conversationId()) : null,
                    AgentApprovalWaitLifecycle.NOOP, "unresolved-root-agent",
                    org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolsEnabled ? ToolPolicy.FULL : ToolPolicy.NONE, 0,
                    request != null && request.conversationId() != null
                            ? AiMutationApprovalScope.root(request.conversationId()) : null,
                    AgentApprovalWaitLifecycle.NOOP, defaultAgentId(
                            toolsEnabled ? ToolPolicy.FULL : ToolPolicy.NONE, 0),
                    defaultPurpose(toolsEnabled ? ToolPolicy.FULL : ToolPolicy.NONE, 0));
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth,
                    request != null && request.conversationId() != null
                            ? AiMutationApprovalScope.root(request.conversationId()) : null,
                    AgentApprovalWaitLifecycle.NOOP, defaultAgentId(toolPolicy, agentDepth),
                    defaultPurpose(toolPolicy, agentDepth));
        }


        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiMutationApprovalScope approvalScope) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                    AgentApprovalWaitLifecycle.NOOP, defaultAgentId(toolPolicy, agentDepth),
                    defaultPurpose(toolPolicy, agentDepth));
        }

        public Context withApprovalScope(AiMutationApprovalScope scope) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth, scope,
                    approvalWaitLifecycle, agentId, executionPurpose, guardrailDecisionIds,
                    workflowObservationContext, agentToolBinding, middlewareState);
        }

        public Context withApprovalWaitLifecycle(AgentApprovalWaitLifecycle lifecycle) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                    approvalScope, lifecycle, agentId, executionPurpose, guardrailDecisionIds,
                    workflowObservationContext, agentToolBinding, middlewareState);
        }

        public Context withAgentIdentity(String identity,
                                         org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose purpose) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                    approvalScope, approvalWaitLifecycle, identity, purpose,
                    guardrailDecisionIds, workflowObservationContext, agentToolBinding,
                    middlewareState);
        }

        public Context withUserMessage(UserMessage message) {
            return new Context(request, history, Objects.requireNonNull(message, "user message"),
                    requester, recorder, toolsEnabled, streamVisibleContent, toolPolicy,
                    agentDepth, approvalScope, approvalWaitLifecycle, agentId,
                    executionPurpose, guardrailDecisionIds, workflowObservationContext,
                    agentToolBinding, middlewareState);
        }

        /** Adds bounded, server-authored output-policy feedback to the next retry turn. */
        public Context withRetryFeedback(String feedback) {
            if (!StringUtils.hasText(feedback)) return this;
            String current = Objects.requireNonNullElse(userMessage.getText(), "");
            UserMessage revised = UserMessage.builder()
                    .text(current + "\n\nOUTPUT_POLICY_FEEDBACK\n" + feedback.strip())
                    .media(userMessage.getMedia())
                    .build();
            return withUserMessage(revised);
        }

        public Context withGuardrailDecisions(List<String> decisionIds) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                    approvalScope, approvalWaitLifecycle, agentId, executionPurpose,
                    decisionIds, workflowObservationContext, agentToolBinding,
                    middlewareState);
        }

        public Context withWorkflowObservationContext(Map<String, Object> value) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                    approvalScope, approvalWaitLifecycle, agentId, executionPurpose,
                    guardrailDecisionIds, value, agentToolBinding, middlewareState);
        }

        /** Applies the non-root execution scope required for every model-authored assignment. */
        public Context forWorkflowAssignment(ToolPolicy value, String assignedAgentId) {
            ToolPolicy reduced = value != null ? value : ToolPolicy.NONE;
            return new Context(request, history, userMessage, requester, recorder,
                    reduced != ToolPolicy.NONE, false, reduced, 1,
                    approvalScope, approvalWaitLifecycle, assignedAgentId,
                    org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.WORKER,
                    guardrailDecisionIds, workflowObservationContext, agentToolBinding,
                    middlewareState);
        }

        /** Returns a context whose tools are explicitly owned by the Agent definition. */
        public Context withToolBinding(AgentToolBinding binding) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                    approvalScope, approvalWaitLifecycle, agentId, executionPurpose,
                    guardrailDecisionIds, workflowObservationContext,
                    Objects.requireNonNull(binding, "tool binding"), middlewareState);
        }

        public Context {
            history = history != null ? List.copyOf(history) : List.of();
            toolPolicy = toolsEnabled
                    ? toolPolicy != null ? toolPolicy : ToolPolicy.FULL
                    : ToolPolicy.NONE;
            approvalWaitLifecycle = approvalWaitLifecycle != null
                    ? approvalWaitLifecycle : AgentApprovalWaitLifecycle.NOOP;
            agentId = org.springframework.util.StringUtils.hasText(agentId)
                    ? agentId.strip().toLowerCase(java.util.Locale.ROOT) : defaultAgentId(toolPolicy, agentDepth);
            executionPurpose = executionPurpose != null
                    ? executionPurpose : defaultPurpose(toolPolicy, agentDepth);
            guardrailDecisionIds = guardrailDecisionIds != null
                    ? guardrailDecisionIds.stream().filter(Objects::nonNull).distinct().toList()
                    : List.of();
            workflowObservationContext = workflowObservationContext != null
                    ? Map.copyOf(workflowObservationContext) : Map.of();
            middlewareState = middlewareState != null ? middlewareState : new MiddlewareState();
            if (agentDepth < 0 || agentDepth > 1) {
                throw new IllegalArgumentException("AI agent depth must be 0 or 1.");
            }
        }

        private static String defaultAgentId(ToolPolicy toolPolicy, int agentDepth) {
            if (agentDepth > 0) return "worker-agent";
            return toolPolicy == ToolPolicy.NONE ? "internal-agent" : "unresolved-root-agent";
        }

        private static org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose defaultPurpose(
                ToolPolicy toolPolicy, int agentDepth) {
            if (agentDepth > 0) {
                return org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.WORKER;
            }
            return toolPolicy == ToolPolicy.NONE
                    ? org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.SYNTHESIS
                    : org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE;
        }
    }

    public enum ToolPolicy {
        NONE,
        READ_ONLY,
        FULL
    }

    public record Result(String answer, Map<String, Object> traceMetadata) {
        public Result(String answer) {
            this(answer, Map.of());
        }

        public Result {
            traceMetadata = traceMetadata != null ? Map.copyOf(traceMetadata) : Map.of();
        }

        public Result withExecutionIdentity(String agentId, String modelId, String purpose) {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>(traceMetadata);
            metadata.put("agentId", agentId);
            metadata.put("modelId", modelId);
            metadata.put("executionPurpose", purpose);
            return new Result(answer, metadata);
        }
    }

}

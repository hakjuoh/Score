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
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.ConnectCenterAssistantAgent;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
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
    private static final String PAGE_CONTEXT_REFERENCE =
            "Supplied separately in the request-scoped user-context block.";

    private final ScoreAiModelRegistry models;
    private final ConnectCenterMcpClientFactory mcpClients;
    private final ToolSearchToolCallingAdvisor toolSearchAdvisor;
    private final Agent rootAgent;
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

    @Autowired
    public AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                          ToolSearchToolCallingAdvisor toolSearchAdvisor,
                          ConnectCenterAssistantAgent rootAgent,
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
                          ObjectProvider<ExecutionObserver> executionObservers) {
        this(models, mcpClients, toolSearchAdvisor, (Agent) rootAgent, mutationGuard,
                elicitations, providerRetry, optionsFactory, approvalCoordinator,
                toolGuardrails, callbackToolAdapter, springAiToolAdapter,
                modelInputGuardrails, requests, instructions, executionObservers);
    }

    AiChatExecutor(ScoreAiModelRegistry models,
                   ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   Agent rootAgent,
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
        this(models, mcpClients, toolSearchAdvisor, rootAgent, mutationGuard, elicitations,
                providerRetry, optionsFactory, approvalCoordinator, toolGuardrails,
                callbackToolAdapter, springAiToolAdapter, modelInputGuardrails, requests,
                AiExecutionInstructions.bundled(), executionObservers);
    }

    private AiChatExecutor(ScoreAiModelRegistry models,
                           ConnectCenterMcpClientFactory mcpClients,
                           ToolSearchToolCallingAdvisor toolSearchAdvisor,
                           Agent rootAgent,
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
                           ObjectProvider<ExecutionObserver> executionObservers) {
        this.models = models;
        this.mcpClients = mcpClients;
        this.toolSearchAdvisor = toolSearchAdvisor;
        this.rootAgent = Objects.requireNonNull(rootAgent, "rootAgent");
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
        this.observer = ExecutionObserver.composite(executionObservers != null
                ? executionObservers.orderedStream().toList() : List.of());
    }

    /** Compatibility constructor for focused executor tests. */
    AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   Agent rootAgent,
                   AiMutationToolGuard mutationGuard,
                   AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory) {
        this(models, mcpClients, toolSearchAdvisor, rootAgent, mutationGuard,
                elicitations, providerRetry, optionsFactory, null, null, null, null, null,
                null, null);
    }

    /** Compatibility constructor for approval-coordination tests. */
    AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   Agent rootAgent,
                   AiMutationToolGuard mutationGuard,
                   AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory,
                   AiMutationApprovalCoordinator approvalCoordinator) {
        this(models, mcpClients, toolSearchAdvisor, rootAgent, mutationGuard,
                elicitations, providerRetry, optionsFactory, approvalCoordinator,
                null, null, null, null, null, null);
    }

    public Result execute(Context context) {
        boolean rootPersona = rootPersona(context);
        AgentDefinition rootDefinition = rootPersona ? rootAgent.definition() : null;
        Context identifiedContext = rootPersona
                ? context.withAgentIdentity(rootDefinition.id().value(), context.executionPurpose())
                : context;
        return execute(identifiedContext, rootDefinition);
    }

    private Result execute(Context context, AgentDefinition rootDefinition) {
        ExecutionState state = new ExecutionState();
        ExecutionScope scope = executionScope(context);
        observe("agent.run.started", scope, context.agentId(), context.request().modelName(), null);
        try {
            Result result;
            if (context.toolPolicy() == ToolPolicy.NONE) {
                // Tool-less calls (planner, evaluator, no-tool leaves) never consult the
                // MCP registry, so they must not pay the per-call MCP handshake.
                result = execute(context, null, state, rootDefinition);
            } else {
                try (ConnectCenterMcpClientFactory.McpSession mcp = elicitations != null
                        ? mcpClients.open(context.requester(), elicitation -> handleElicitation(
                        context, context.request(), context.recorder(), elicitation))
                        : mcpClients.open(context.requester())) {
                    result = execute(context, mcp, state, rootDefinition);
                }
            }
            Result identified = result.withExecutionIdentity(context.agentId(),
                    context.request().modelName(), context.executionPurpose().name());
            observe("agent.run.completed", scope, context.agentId(),
                    context.request().modelName(), null);
            return identified;
        } catch (RuntimeException failure) {
            observe("agent.run.failed", scope, context.agentId(),
                    context.request().modelName(), failure);
            if (failure instanceof AgentInputRefusedException refused) {
                throw refused.identifiedBy(new Agent.AgentId(context.agentId()));
            }
            throw failure;
        }
    }

    public String rootAgentId() {
        return rootAgent.id().value();
    }

    /** Provider-neutral Agent port used by Gateway, Guardrail, and other no-transport runs. */
    public AgentRunResult executeAgent(AgentInvocation invocation) {
        observe("agent.run.started", invocation.scope(), invocation.agent().id().value(),
                invocation.agent().model().id().value(), null);
        try {
            AgentRunResult result = executeAgentInternal(invocation);
            observe("agent.run.completed", invocation.scope(), invocation.agent().id().value(),
                    invocation.agent().model().id().value(), null);
            return result;
        } catch (RuntimeException failure) {
            observe("agent.run.failed", invocation.scope(), invocation.agent().id().value(),
                    invocation.agent().model().id().value(), failure);
            if (failure instanceof AgentInputRefusedException refused) {
                throw refused.identifiedBy(invocation.agent().id());
            }
            throw failure;
        }
    }

    private AgentRunResult executeAgentInternal(AgentInvocation invocation) {
        String modelId = invocation.agent().model().id().value();
        List<AiMessage> assembled = new ArrayList<>();
        assembled.add(new AiMessage.System(invocation.agent().instruction().value()));
        assembled.addAll(invocation.history());
        assembled.add(invocation.request());
        AgentInputGuardrailChain.Outcome checked = modelInputGuardrails != null
                ? modelInputGuardrails.evaluate(new AgentInputGuardrail.Request(
                AgentInputGuardrail.Scope.MODEL, invocation.request(), assembled,
                invocation.scope(), Map.of("agent_id", invocation.agent().id().value())))
                : AgentInputGuardrailChain.Outcome.allowed(invocation.request(), List.of());
        if (!checked.allowed()) throw new AgentInputRefusedException(checked.refusal());

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(invocation.agent().instruction().value()));
        invocation.history().stream().map(this::toSpringMessage).forEach(messages::add);
        messages.add(toSpringMessage(checked.input()));
        ChatClient.Builder builder = models.clientBuilder(modelId);
        if (!invocation.agent().tools().isEmpty()) {
            if (springAiToolAdapter == null) {
                throw new IllegalStateException("The Spring AI Tool adapter is unavailable.");
            }
            builder.defaultTools(springAiToolAdapter.adapt(
                    invocation.agent().tools(), invocation.tools(), invocation.scope()));
        }
        String reasoningEffort = models.resolveReasoningEffort(modelId, null);
        ChatResponse response = builder.build().prompt()
                .options(optionsFactory.create(modelId, reasoningEffort, null).mutate())
                .messages(messages).call().chatResponse();
        String answer = visibleContent(response);
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("The Agent returned an empty response.");
        }
        AiMessage.Assistant assistant = new AiMessage.Assistant(answer);
        return new AgentRunResult(assistant, List.of(assistant), Optional.empty(),
                new AgentRunResult.RunMetadata(invocation.agent().id(),
                        invocation.agent().model().id(), null,
                        Map.of("guardrail_decisions", checked.decisions().stream()
                                .map(GuardrailDecision::decisionId).toList())));
    }

    private void observe(String type, ExecutionScope scope, String agentId,
                         String modelId, RuntimeException failure) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("agent_id", agentId);
        attributes.put("model_id", modelId);
        if (failure != null) {
            attributes.put("failure_type", failure.getClass().getSimpleName());
        }
        observer.observe(ExecutionObservation.of(type, scope, Map.copyOf(attributes)));
    }

    private Message toSpringMessage(AiMessage message) {
        if (message instanceof AiMessage.System system) return new SystemMessage(system.content());
        if (message instanceof AiMessage.User user) {
            return SpringAiUserMessageAdapter.toSpring(user);
        }
        if (message instanceof AiMessage.Assistant assistant) {
            return new AssistantMessage(assistant.content());
        }
        if (message instanceof AiMessage.ToolCall call) {
            return AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall(call.callId(), "function", call.toolName(),
                            call.arguments()))).build();
        }
        AiMessage.ToolResult result = (AiMessage.ToolResult) message;
        return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                result.callId(), result.toolName(), result.result()))).build();
    }

    private Result execute(Context context, ConnectCenterMcpClientFactory.McpSession mcp,
                           ExecutionState executionState, AgentDefinition rootDefinition) {
        var request = context.request();
        AiTrajectoryRecorder recorder = context.recorder();
        ChatOptions options = optionsFactory.create(
                request.modelName(), request.reasoningEffort(), request.routeManifest());
        ScoreAiModelRegistry.ModelConfiguration model = models.modelConfiguration(request.modelName());
        long toolOutputTokenLimit = model.contextBudget() != null
                && model.contextBudget().toolOutputTokenLimit() != null
                ? model.contextBudget().toolOutputTokenLimit() : Long.MAX_VALUE;
        {
            ChatClient.Builder assistantBuilder = models.clientBuilder(request.modelName())
                    .defaultAdvisors(new TrajectoryRecordingAdvisor(recorder));
            AiMutationToolGuard.GuardedToolSession guardedSession = null;
            org.springframework.ai.tool.ToolCallbackProvider executableTools = null;
            if (mcp != null && mcp.client() != null && context.toolPolicy() != ToolPolicy.NONE) {
                recorder.readOnlyToolNames(mcp.readOnlyToolNames());
                boolean commonToolGateway = toolGuardrails != null
                        && callbackToolAdapter != null && springAiToolAdapter != null;
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
                                    mcp.tools(),
                                    mcp.readOnlyToolNames())
                            : null;
                }
                var guardedTools = context.toolPolicy() == ToolPolicy.READ_ONLY
                        ? mutationGuard != null
                                ? mutationGuard.readOnly(mcp.tools(), mcp.readOnlyToolNames())
                                : (org.springframework.ai.tool.ToolCallbackProvider) () ->
                                        new org.springframework.ai.tool.ToolCallback[0]
                        : commonToolGateway ? mcp.tools()
                        : guardedSession != null ? guardedSession : mcp.tools();
                if (commonToolGateway) {
                    var coreTools = callbackToolAdapter.adapt(guardedTools, mcp.readOnlyToolNames());
                    ExecutionScope scope = executionScope(context);
                    long rawByteLimit = toolOutputTokenLimit == Long.MAX_VALUE
                            ? 16L * 1024L * 1024L
                            : Math.max(4096L, Math.min(16L * 1024L * 1024L,
                            toolOutputTokenLimit * 4L));
                    ToolExecutionGateway gateway = new ToolExecutionGateway(coreTools, toolGuardrails,
                            guardedSession != null ? List.of(guardedSession) : List.of(),
                            scopeToCheck -> {
                                if (Thread.currentThread().isInterrupted()
                                        || requests != null
                                        && requests.shouldDiscardResult(scopeToCheck.requestId())) {
                                    throw new CancellationException(
                                            "The assistant request stopped before Tool execution.");
                                }
                            },
                            observer,
                            executionState, rawByteLimit);
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
                    executionState, rootDefinition);
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
                        executionState, rootDefinition);
            }
            if (isTextualToolCallPlaceholder(answer)) {
                throw new IllegalStateException(
                        "The assistant repeatedly returned a textual tool-call placeholder.");
            }
            List<Message> approvalMessages = new ArrayList<>(messages);
            int approvalBarrierCount = 0;
            int approvedMutationCount = 0;
            int deniedMutationCount = 0;
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
                approvedMutationCount += executed;
                deniedMutationCount += resolutions.size() - executed;
                approvalMessages.add(new AssistantMessage(answer));
                resolutions.forEach(resolution -> addResolvedMutation(
                        approvalMessages, resolution, recorder, toolOutputTokenLimit));
                approvalMessages.add(new UserMessage(instructions.render(
                        AiExecutionInstructions.Template.APPROVAL_CONTINUATION).value()));
                answer = invoke(assistant, options, request, approvalMessages, recorder,
                        false, internalPersona, executionScope(context), executionState,
                        rootDefinition);
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
                        rootDefinition);
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
                        "deniedMutationCount", deniedMutationCount));
            }
            return new Result(answer);
        }
    }

    /** Internal execution purposes own their instructions and never inherit ROOT. */
    private boolean internalPersona(Context context) {
        return !rootPersona(context);
    }

    private boolean rootPersona(Context context) {
        return context.executionPurpose()
                == org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE;
    }

    private ExecutionScope executionScope(Context context) {
        String requesterId = context.requester() != null && context.requester().userId() != null
                ? context.requester().userId().value().toString()
                : context.requester() != null && StringUtils.hasText(context.requester().username())
                ? context.requester().username() : "unknown";
        return new ExecutionScope(context.request().requestId(), context.request().conversationId(),
                requesterId, 0L, context.executionPurpose(), context.guardrailDecisionIds());
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
                          boolean streamVisibleContent) {
        return invoke(assistant, options, request, messages, recorder,
                streamVisibleContent, false, new ExecutionScope(
                        request.requestId(), request.conversationId(), "request-owner", 0L,
                        ExecutionScope.Purpose.USER_RESPONSE, List.of()), new ExecutionState(),
                rootAgent.definition());
    }

    private String invoke(ChatClient assistant, ChatOptions options,
                          ChatRequest request,
                          List<Message> messages, AiTrajectoryRecorder recorder,
                          boolean streamVisibleContent, boolean internalPersona,
                          ExecutionScope scope, ExecutionState executionState,
                          AgentDefinition rootDefinition) {
        if (providerRetry == null) {
            return attemptInvoke(assistant, options, request, messages, recorder,
                    streamVisibleContent, internalPersona, scope, rootDefinition);
        }
        // Transient provider failures are retried with visible backoff. An attempt
        // that executed a data-changing tool is terminal: the recorder's mutation
        // count is the executor's replay fence.
        return providerRetry.execute(request, recorder,
                () -> Math.max(executionState.completedMutations(),
                        recorder.executedMutationToolCallCount()), executionState,
                () -> attemptInvoke(assistant, options, request, messages, recorder,
                        streamVisibleContent, internalPersona, scope, rootDefinition));
    }

    private String attemptInvoke(ChatClient assistant, ChatOptions options,
                                 ChatRequest request,
                                 List<Message> messages, AiTrajectoryRecorder recorder,
                                 boolean streamVisibleContent, boolean internalPersona,
                                 ExecutionScope scope, AgentDefinition rootDefinition) {
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
        String stableSystemPrompt = internalPersona
                ? null : withRouteManifest(
                        Objects.requireNonNull(rootDefinition, "rootDefinition").instruction()
                                .render(systemPromptParameters(request)).value(), request, instructions);
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
        if (stableSystemPrompt != null) {
            prompt = prompt.system(system -> system.text(stableSystemPrompt));
        }
        prompt
                    .messages(requestMessages)
                    .advisors(advisor -> advisor
                            .param(ChatMemory.CONVERSATION_ID, request.conversationId())
                            .param(AiTrajectoryRecorder.PHASE_CONTEXT_KEY, "assistant"))
                    .stream()
                    .chatResponse()
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
        List<AiMessage> assembled = messages.stream().map(this::coreMessage).toList();
        AiMessage.User input = guardedUserIndex >= 0
                ? (AiMessage.User) assembled.get(guardedUserIndex) : new AiMessage.User("");
        AgentInputGuardrailChain.Outcome outcome = modelInputGuardrails.evaluate(
                new AgentInputGuardrail.Request(AgentInputGuardrail.Scope.MODEL,
                        input, assembled, scope, Map.of("model", request.modelName())));
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

    private AiMessage coreMessage(Message message) {
        if (message instanceof SystemMessage system) return new AiMessage.System(system.getText());
        if (message instanceof UserMessage user) return SpringAiUserMessageAdapter.toCore(user);
        if (message instanceof AssistantMessage assistant) return new AiMessage.Assistant(assistant.getText());
        return new AiMessage.ToolResult("tool-result", "tool", Objects.requireNonNullElse(message.getText(), ""));
    }

    private boolean isTextualToolCallPlaceholder(String answer) {
        return StringUtils.hasText(answer)
                && TEXTUAL_TOOL_CALL_PLACEHOLDER.matcher(answer).find();
    }

    static Map<String, Object> systemPromptParameters(ChatRequest request) {
        return Map.of(
                "mutationConfirmationRequired", AiMutationToolGuard.MUTATION_CONFIRMATION_REQUIRED,
                "mutationApprovalPolicy", AiMutationPermissionMode.resolve(
                        request != null ? request.permissionMode() : null).assistantPolicy(),
                "requestStopping", AiMutationToolGuard.REQUEST_STOPPING,
                // Preserve compatibility with externally mounted prompts that
                // still contain ${pageContext}, without putting volatile page
                // data in the cacheable system prompt.
                "pageContext", PAGE_CONTEXT_REFERENCE);
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

    static String withRouteManifest(String stableSystemPrompt, ChatRequest request) {
        return withRouteManifest(stableSystemPrompt, request, AiExecutionInstructions.bundled());
    }

    private static String withRouteManifest(String stableSystemPrompt, ChatRequest request,
                                            AiExecutionInstructions instructions) {
        if (request == null || request.routeManifest() == null) {
            return stableSystemPrompt;
        }
        return stableSystemPrompt + "\n\n" + instructions.render(
                AiExecutionInstructions.Template.UI_ROUTE_MANIFEST_CONTEXT,
                Map.of("routeManifest", request.routeManifest().promptText())).value();
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

    public record Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                          ScoreUser requester, AiTrajectoryRecorder recorder,
                          boolean toolsEnabled, boolean streamVisibleContent,
                          ToolPolicy toolPolicy, int agentDepth,
                          AiMutationApprovalScope approvalScope,
                          ApprovalWaitLifecycle approvalWaitLifecycle,
                          String agentId,
                          org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose executionPurpose,
                          List<String> guardrailDecisionIds) {

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiMutationApprovalScope approvalScope,
                       ApprovalWaitLifecycle approvalWaitLifecycle,
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
                       ApprovalWaitLifecycle approvalWaitLifecycle) {
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
                    ApprovalWaitLifecycle.NOOP, "unresolved-root-agent",
                    org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolsEnabled ? ToolPolicy.FULL : ToolPolicy.NONE, 0,
                    request != null && request.conversationId() != null
                            ? AiMutationApprovalScope.root(request.conversationId()) : null,
                    ApprovalWaitLifecycle.NOOP, defaultAgentId(
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
                    ApprovalWaitLifecycle.NOOP, defaultAgentId(toolPolicy, agentDepth),
                    defaultPurpose(toolPolicy, agentDepth));
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiMutationApprovalScope approvalScope) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                    ApprovalWaitLifecycle.NOOP, defaultAgentId(toolPolicy, agentDepth),
                    defaultPurpose(toolPolicy, agentDepth));
        }

        public Context withApprovalScope(AiMutationApprovalScope scope) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth, scope,
                    approvalWaitLifecycle, agentId, executionPurpose, guardrailDecisionIds);
        }

        public Context withApprovalWaitLifecycle(ApprovalWaitLifecycle lifecycle) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                    approvalScope, lifecycle, agentId, executionPurpose, guardrailDecisionIds);
        }

        public Context withAgentIdentity(String identity,
                                         org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose purpose) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                    approvalScope, approvalWaitLifecycle, identity, purpose,
                    guardrailDecisionIds);
        }

        public Context withGuardrailDecisions(List<String> decisionIds) {
            return new Context(request, history, userMessage, requester, recorder,
                    toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                    approvalScope, approvalWaitLifecycle, agentId, executionPurpose,
                    decisionIds);
        }

        public Context {
            history = history != null ? List.copyOf(history) : List.of();
            toolPolicy = toolsEnabled
                    ? toolPolicy != null ? toolPolicy : ToolPolicy.FULL
                    : ToolPolicy.NONE;
            approvalWaitLifecycle = approvalWaitLifecycle != null
                    ? approvalWaitLifecycle : ApprovalWaitLifecycle.NOOP;
            agentId = org.springframework.util.StringUtils.hasText(agentId)
                    ? agentId.strip().toLowerCase(java.util.Locale.ROOT) : defaultAgentId(toolPolicy, agentDepth);
            executionPurpose = executionPurpose != null
                    ? executionPurpose : defaultPurpose(toolPolicy, agentDepth);
            guardrailDecisionIds = guardrailDecisionIds != null
                    ? guardrailDecisionIds.stream().filter(Objects::nonNull).distinct().toList()
                    : List.of();
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

    /** Releases scarce worker admission while a retained agent waits for user approval. */
    public interface ApprovalWaitLifecycle {

        ApprovalWaitLifecycle NOOP = new ApprovalWaitLifecycle() {
            @Override
            public void suspendForApproval() {
            }

            @Override
            public void resumeAfterApproval() {
            }
        };

        void suspendForApproval();

        void resumeAfterApproval();
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

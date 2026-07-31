package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatSession;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareChain;
import org.oagi.score.gateway.http.api.ai_management.middleware.MiddlewareState;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.provider.AiProviderRetryExecutor;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeToolGuard;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiPlatformToolProvider;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiChatOptionsFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stable facade for Spring AI chat execution; runtime concerns live in focused collaborators. */
@Component
public final class AiChatExecutor {

    private final AiChatAgentRuntime runtime;

    @Autowired
    public AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                          ToolSearchToolCallingAdvisor toolSearchAdvisor,
                          AiChangeToolGuard changeGuard,
                          AiElicitationService elicitations,
                          AiProviderRetryExecutor providerRetry,
                          ScoreAiChatOptionsFactory optionsFactory,
                          AiChangeApprovalCoordinator approvalCoordinator,
                          ToolGuardrailRegistry toolGuardrails,
                          SpringAiCallbackToolSetAdapter callbackToolAdapter,
                          SpringAiToolAdapter springAiToolAdapter,
                          AgentInputGuardrailChain modelInputGuardrails,
                          AiRequestRegistry requests,
                          AiExecutionInstructions instructions,
                          ScoreAiObservability observability,
                          AiMiddlewareChain middleware,
                          AiPlatformToolProvider platformTools,
                          ScoreAiProperties properties,
                          ObjectProvider<ExecutionObserver> executionObservers) {
        AiExecutionInstructions resolvedInstructions =
                Objects.requireNonNull(instructions, "instructions");
        ScoreAiObservability resolvedObservability =
                observability != null ? observability : ScoreAiObservability.noop();
        AiMiddlewareChain resolvedMiddleware =
                middleware != null ? middleware : AiMiddlewareChain.none();
        ExecutionObserver observer = executionObservers != null
                ? executionObservers.getIfAvailable(ExecutionObserver::noop)
                : ExecutionObserver.noop();
        AiModelInputGuard inputGuard =
                new AiModelInputGuard(modelInputGuardrails, resolvedObservability);
        AiChatModelInvoker modelInvoker = new AiChatModelInvoker(
                providerRetry, requests, resolvedInstructions, inputGuard);
        boolean toolSearchEnabled = properties == null
                || properties.getTools().getToolSearch().isEnabled();
        AiChatToolSessionFactory toolSessions = new AiChatToolSessionFactory(
                toolSearchAdvisor, changeGuard, toolGuardrails, callbackToolAdapter,
                springAiToolAdapter, requests, resolvedMiddleware, platformTools,
                toolSearchEnabled, approvalCoordinator != null, observer);
        AiChatContinuationRunner continuations = new AiChatContinuationRunner(
                approvalCoordinator, resolvedInstructions, modelInvoker);
        AiChatConversationRuntime conversations = new AiChatConversationRuntime(
                models, elicitations, optionsFactory, resolvedInstructions,
                resolvedObservability, toolSessions, continuations, modelInvoker);
        this.runtime = new AiChatAgentRuntime(models, mcpClients, optionsFactory,
                springAiToolAdapter, modelInputGuardrails, requests, observer,
                resolvedObservability, conversations, providerRetry);
    }

    /** Compatibility constructor for callers predating configurable middleware. */
    public AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                          ToolSearchToolCallingAdvisor toolSearchAdvisor,
                          AiChangeToolGuard changeGuard,
                          AiElicitationService elicitations,
                          AiProviderRetryExecutor providerRetry,
                          ScoreAiChatOptionsFactory optionsFactory,
                          AiChangeApprovalCoordinator approvalCoordinator,
                          ToolGuardrailRegistry toolGuardrails,
                          SpringAiCallbackToolSetAdapter callbackToolAdapter,
                          SpringAiToolAdapter springAiToolAdapter,
                          AgentInputGuardrailChain modelInputGuardrails,
                          AiRequestRegistry requests,
                          AiExecutionInstructions instructions,
                          ScoreAiObservability observability,
                          ObjectProvider<ExecutionObserver> executionObservers) {
        this(models, mcpClients, toolSearchAdvisor, changeGuard, elicitations,
                providerRetry, optionsFactory, approvalCoordinator, toolGuardrails,
                callbackToolAdapter, springAiToolAdapter, modelInputGuardrails, requests,
                instructions, observability, AiMiddlewareChain.none(), null,
                new ScoreAiProperties(), executionObservers);
    }

    AiChatExecutor(ScoreAiModelRegistry models,
                   ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   AiChangeToolGuard changeGuard,
                   AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory,
                   AiChangeApprovalCoordinator approvalCoordinator,
                   ToolGuardrailRegistry toolGuardrails,
                   SpringAiCallbackToolSetAdapter callbackToolAdapter,
                   SpringAiToolAdapter springAiToolAdapter,
                   AgentInputGuardrailChain modelInputGuardrails,
                   AiRequestRegistry requests,
                   ObjectProvider<ExecutionObserver> executionObservers) {
        this(models, mcpClients, toolSearchAdvisor, changeGuard, elicitations,
                providerRetry, optionsFactory, approvalCoordinator, toolGuardrails,
                callbackToolAdapter, springAiToolAdapter, modelInputGuardrails, requests,
                new ScoreAiProperties(), executionObservers);
    }

    AiChatExecutor(ScoreAiModelRegistry models,
                   ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   AiChangeToolGuard changeGuard,
                   AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory,
                   AiChangeApprovalCoordinator approvalCoordinator,
                   ToolGuardrailRegistry toolGuardrails,
                   SpringAiCallbackToolSetAdapter callbackToolAdapter,
                   SpringAiToolAdapter springAiToolAdapter,
                   AgentInputGuardrailChain modelInputGuardrails,
                   AiRequestRegistry requests,
                   ScoreAiProperties properties,
                   ObjectProvider<ExecutionObserver> executionObservers) {
        this(models, mcpClients, toolSearchAdvisor, changeGuard, elicitations,
                providerRetry, optionsFactory, approvalCoordinator, toolGuardrails,
                callbackToolAdapter, springAiToolAdapter, modelInputGuardrails, requests,
                AiExecutionInstructions.bundled(), ScoreAiObservability.noop(),
                AiMiddlewareChain.none(), null, properties, executionObservers);
    }

    AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   AiChangeToolGuard changeGuard, AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory) {
        this(models, mcpClients, toolSearchAdvisor, changeGuard,
                elicitations, providerRetry, optionsFactory, null, null, null, null, null,
                null, null);
    }

    AiChatExecutor(ScoreAiModelRegistry models, ConnectCenterMcpClientFactory mcpClients,
                   ToolSearchToolCallingAdvisor toolSearchAdvisor,
                   AiChangeToolGuard changeGuard, AiElicitationService elicitations,
                   AiProviderRetryExecutor providerRetry,
                   ScoreAiChatOptionsFactory optionsFactory,
                   AiChangeApprovalCoordinator approvalCoordinator) {
        this(models, mcpClients, toolSearchAdvisor, changeGuard,
                elicitations, providerRetry, optionsFactory, approvalCoordinator,
                null, null, null, null, null, null);
    }

    public AgentChatResult executeAgentChat(AgentChatSession session) {
        return runtime.executeAgentChat(session);
    }

    static org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot usageDelta(
            org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot before,
            org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot after) {
        return AiChatAgentRuntime.usageDelta(before, after);
    }

    Result execute(Context context, Agent.Instruction instruction) {
        return execute(context, instruction, () -> { });
    }

    Result execute(Context context, Agent.Instruction instruction, Runnable progress) {
        return execute(context, instruction, progress, WorkflowRunControl.NOOP);
    }

    Result execute(Context context, Agent.Instruction instruction,
                   Runnable progress, WorkflowRunControl runControl) {
        return runtime.execute(context, Objects.requireNonNull(instruction, "instruction"),
                Objects.requireNonNull(progress, "progress"),
                Objects.requireNonNull(runControl, "runControl"));
    }

    AgentRunResult executeAgent(AgentInvocation invocation) {
        return runtime.executeAgent(invocation);
    }

    static String agentFailureEvent(AiRequestRegistry requests, String requestId,
                                    RuntimeException failure) {
        return AiChatAgentRuntime.agentFailureEvent(requests, requestId, failure);
    }

    static String requestScopedInput(
            org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest request) {
        return AiChatModelInvoker.requestScopedInput(request);
    }

    record Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                          ScoreUser requester, AiTrajectoryRecorder recorder,
                          boolean toolsEnabled, boolean streamVisibleContent,
                          ToolPolicy toolPolicy, int agentDepth,
                          AiChangeApprovalScope approvalScope,
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
                       AiChangeApprovalScope approvalScope,
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
                       AiChangeApprovalScope approvalScope,
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
                       AiChangeApprovalScope approvalScope,
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
                       AiChangeApprovalScope approvalScope,
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
                       AiChangeApprovalScope approvalScope,
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
                            ? AiChangeApprovalScope.root(request.conversationId()) : null,
                    AgentApprovalWaitLifecycle.NOOP, "unresolved-root-agent",
                    org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope.Purpose.USER_RESPONSE);
        }

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolsEnabled ? ToolPolicy.FULL : ToolPolicy.NONE, 0,
                    request != null && request.conversationId() != null
                            ? AiChangeApprovalScope.root(request.conversationId()) : null,
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
                            ? AiChangeApprovalScope.root(request.conversationId()) : null,
                    AgentApprovalWaitLifecycle.NOOP, defaultAgentId(toolPolicy, agentDepth),
                    defaultPurpose(toolPolicy, agentDepth));
        }


        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder,
                       boolean toolsEnabled, boolean streamVisibleContent,
                       ToolPolicy toolPolicy, int agentDepth,
                       AiChangeApprovalScope approvalScope) {
            this(request, history, userMessage, requester, recorder, toolsEnabled,
                    streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                    AgentApprovalWaitLifecycle.NOOP, defaultAgentId(toolPolicy, agentDepth),
                    defaultPurpose(toolPolicy, agentDepth));
        }

        public Context withApprovalScope(AiChangeApprovalScope scope) {
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

package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentApprovalWaitLifecycle;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangePermissionMode;
import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeToolGuard;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable application context for a complete Chat turn.
 *
 * <p>This type stores canonical Agent messages and neutral policies. The Spring
 * AI provider context is created later by {@link SpringAiExecutionContextMapper}
 * at the execution-port boundary.</p>
 */
public final class ChatExecutionContext implements AgentExecutionContext {

    private static final String PAGE_CONTEXT_REFERENCE =
            "Supplied separately in the request-scoped user-context block.";

    private final ChatRequest request;
    private final List<AiMessage> history;
    private final AiMessage.User userMessage;
    private final ScoreUser requester;
    private final AgentExecutionRecorder recorder;
    private final boolean toolsEnabled;
    private final boolean streamVisibleContent;
    private final AgentToolPolicy toolPolicy;
    private final int agentDepth;
    private final AiChangeApprovalScope approvalScope;
    private final AgentApprovalWaitLifecycle approvalWaitLifecycle;
    private final String agentId;
    private final ExecutionScope.Purpose executionPurpose;
    private final List<String> guardrailDecisionIds;
    private final Map<String, Object> workflowObservationContext;
    private final AgentToolBinding toolBinding;

    private ChatExecutionContext(ChatRequest request, List<AiMessage> history,
                                 AiMessage.User userMessage, ScoreUser requester,
                                 AgentExecutionRecorder recorder, boolean toolsEnabled,
                                 boolean streamVisibleContent, AgentToolPolicy toolPolicy,
                                 int agentDepth, AiChangeApprovalScope approvalScope,
                                 AgentApprovalWaitLifecycle approvalWaitLifecycle,
                                 String agentId, ExecutionScope.Purpose executionPurpose,
                                 List<String> guardrailDecisionIds,
                                 Map<String, Object> workflowObservationContext,
                                 AgentToolBinding toolBinding) {
        this.request = Objects.requireNonNull(request, "request");
        this.history = history != null ? List.copyOf(history) : List.of();
        this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        this.requester = requester;
        this.recorder = recorder != null ? recorder : AgentExecutionRecorder.noop();
        this.toolsEnabled = toolsEnabled;
        this.streamVisibleContent = streamVisibleContent;
        this.toolPolicy = toolsEnabled
                ? Objects.requireNonNullElse(toolPolicy, AgentToolPolicy.FULL)
                : AgentToolPolicy.NONE;
        if (agentDepth < 0 || agentDepth > 1) {
            throw new IllegalArgumentException("AI agent depth must be 0 or 1.");
        }
        this.agentDepth = agentDepth;
        this.approvalScope = approvalScope;
        this.approvalWaitLifecycle = approvalWaitLifecycle != null
                ? approvalWaitLifecycle : AgentApprovalWaitLifecycle.NOOP;
        this.agentId = normalizeAgentId(agentId, this.toolPolicy, agentDepth);
        this.executionPurpose = executionPurpose != null
                ? executionPurpose : defaultPurpose(this.toolPolicy, agentDepth);
        this.guardrailDecisionIds = guardrailDecisionIds != null
                ? guardrailDecisionIds.stream().filter(Objects::nonNull).distinct().toList()
                : List.of();
        this.workflowObservationContext = workflowObservationContext != null
                ? Map.copyOf(workflowObservationContext) : Map.of();
        this.toolBinding = toolBinding;
    }

    public static ChatExecutionContext fromRequest(ChatRequest request,
                                                   List<Message> history,
                                                   UserMessage userMessage,
                                                   ScoreUser requester,
                                                   AiTrajectoryRecorder recorder,
                                                   boolean toolsEnabled,
                                                   boolean streamVisibleContent) {
        AgentToolPolicy policy = toolsEnabled ? AgentToolPolicy.FULL : AgentToolPolicy.NONE;
        return create(request,
                history != null ? history.stream().map(SpringAiMessageAdapter::toCore).toList()
                        : List.of(),
                SpringAiUserMessageAdapter.toCore(Objects.requireNonNull(userMessage, "userMessage")),
                requester, AgentExecutionRecorderAdapter.of(recorder), toolsEnabled,
                streamVisibleContent, policy, 0,
                request != null && request.conversationId() != null
                        ? AiChangeApprovalScope.root(request.conversationId()) : null);
    }

    public static ChatExecutionContext fromCoreMessages(ChatRequest request,
                                                        List<AiMessage> history,
                                                        AiMessage.User userMessage,
                                                        ScoreUser requester,
                                                        AiTrajectoryRecorder recorder,
                                                        boolean toolsEnabled,
                                                        boolean streamVisibleContent,
                                                        AgentToolPolicy toolPolicy,
                                                        int agentDepth) {
        return fromCoreMessages(request, history, userMessage, requester, recorder,
                toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                request != null && request.conversationId() != null
                        ? AiChangeApprovalScope.root(request.conversationId()) : null);
    }

    public static ChatExecutionContext fromCoreMessages(ChatRequest request,
                                                        List<AiMessage> history,
                                                        AiMessage.User userMessage,
                                                        ScoreUser requester,
                                                        AiTrajectoryRecorder recorder,
                                                        boolean toolsEnabled,
                                                        boolean streamVisibleContent,
                                                        AgentToolPolicy toolPolicy,
                                                        int agentDepth,
                                                        AiChangeApprovalScope approvalScope) {
        return create(request, history, userMessage, requester,
                AgentExecutionRecorderAdapter.of(recorder), toolsEnabled,
                streamVisibleContent, toolPolicy, agentDepth, approvalScope);
    }

    private static ChatExecutionContext create(ChatRequest request, List<AiMessage> history,
                                               AiMessage.User userMessage, ScoreUser requester,
                                               AgentExecutionRecorder recorder,
                                               boolean toolsEnabled,
                                               boolean streamVisibleContent,
                                               AgentToolPolicy toolPolicy, int agentDepth,
                                               AiChangeApprovalScope approvalScope) {
        AgentToolPolicy effective = toolsEnabled
                ? Objects.requireNonNullElse(toolPolicy, AgentToolPolicy.FULL)
                : AgentToolPolicy.NONE;
        return new ChatExecutionContext(request, history, userMessage, requester, recorder,
                toolsEnabled, streamVisibleContent, effective, agentDepth, approvalScope,
                AgentApprovalWaitLifecycle.NOOP, null, null, List.of(), Map.of(), null);
    }

    public static ChatExecutionContext standalone(String requestId, String conversationId,
                                                  String agentId, String modelName,
                                                  AiMessage.User input, ScoreUser requester,
                                                  ExecutionScope.Purpose purpose) {
        ChatRequest request = new ChatRequest(input.content(), requestId, agentId,
                conversationId, null, List.of(), null, modelName, null, null);
        return fromCoreMessages(request, List.of(), input, requester, null,
                false, false, AgentToolPolicy.NONE, 0)
                .withAgentIdentity(agentId, purpose);
    }

    public static ChatExecutionContext require(AgentExecutionContext context) {
        if (context instanceof ChatExecutionContext chat) return chat;
        throw new IllegalArgumentException(
                "The Spring AI Chat adapter requires a ChatExecutionContext.");
    }

    ChatRequest request() {
        return request;
    }

    ScoreUser requester() {
        return requester;
    }

    AgentApprovalWaitLifecycle approvalWaitLifecycle() {
        return approvalWaitLifecycle;
    }

    @Override
    public ChatExecutionContext forAssignedAgent(String childConversationId,
                                                 List<AiMessage> assignedHistory,
                                                 AiMessage.User assignment,
                                                 AgentExecutionRecorder assignedRecorder,
                                                 AgentToolPolicy policy,
                                                 String assignedAgentId,
                                                 AiChangeApprovalScope assignedApprovalScope) {
        AgentToolPolicy effective = Objects.requireNonNullElse(policy, AgentToolPolicy.NONE);
        ChatRequest childRequest = request.withConversationId(childConversationId)
                .withMultiAgent(AiMultiAgentOptions.single());
        return new ChatExecutionContext(childRequest, assignedHistory, assignment, requester,
                Objects.requireNonNull(assignedRecorder, "assignedRecorder"),
                effective != AgentToolPolicy.NONE, false, effective, 1,
                assignedApprovalScope, approvalWaitLifecycle, assignedAgentId,
                ExecutionScope.Purpose.WORKER, guardrailDecisionIds,
                workflowObservationContext, null);
    }

    @Override
    public ChatExecutionContext withToolBinding(AgentToolBinding binding) {
        return copy(userMessage, toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                approvalScope, approvalWaitLifecycle, agentId, executionPurpose,
                guardrailDecisionIds, workflowObservationContext,
                Objects.requireNonNull(binding, "binding"));
    }

    @Override
    public String requestId() {
        return request.requestId();
    }

    @Override
    public String conversationId() {
        return request.conversationId();
    }

    @Override
    public String modelName() {
        return request.modelName();
    }

    @Override
    public String requesterId() {
        if (requester != null && requester.userId() != null) {
            return requester.userId().value().toString();
        }
        return requester != null && StringUtils.hasText(requester.username())
                ? requester.username() : "unknown";
    }

    @Override
    public AiMessage.User userMessage() {
        return userMessage;
    }

    @Override
    public List<AiMessage> history() {
        return history;
    }

    @Override
    public AgentExecutionRecorder recorder() {
        return recorder;
    }

    @Override
    public boolean toolsEnabled() {
        return toolsEnabled;
    }

    @Override
    public AgentToolPolicy toolPolicy() {
        return toolPolicy;
    }

    @Override
    public AgentToolBinding toolBinding() {
        return toolBinding;
    }

    @Override
    public boolean streamVisibleContent() {
        return streamVisibleContent;
    }

    @Override
    public int agentDepth() {
        return agentDepth;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public ExecutionScope.Purpose executionPurpose() {
        return executionPurpose;
    }

    @Override
    public List<String> guardrailDecisionIds() {
        return guardrailDecisionIds;
    }

    @Override
    public AiChangeApprovalScope approvalScope() {
        return approvalScope;
    }

    @Override
    public Map<String, Object> workflowObservationContext() {
        return workflowObservationContext;
    }

    @Override
    public Map<String, Object> instructionParameters() {
        return Map.of(
                "changeConfirmationRequired",
                AiChangeToolGuard.CHANGE_CONFIRMATION_REQUIRED,
                "changeApprovalPolicy",
                AiChangePermissionMode.resolve(request.permissionMode()).assistantPolicy(),
                "requestStopping", AiChangeToolGuard.REQUEST_STOPPING,
                "pageContext", PAGE_CONTEXT_REFERENCE);
    }

    @Override
    public Agent.Instruction finalizeInstruction(Agent.Instruction instruction) {
        Objects.requireNonNull(instruction, "instruction");
        if (executionPurpose != ExecutionScope.Purpose.USER_RESPONSE
                || request.routeManifest() == null) {
            return instruction;
        }
        Agent.Instruction routes = AiExecutionInstructions.bundled().render(
                AiExecutionInstructions.Template.UI_ROUTE_MANIFEST_CONTEXT,
                Map.of("routeManifest", request.routeManifest().promptText()));
        return new Agent.Instruction(instruction.value() + "\n\n" + routes.value());
    }

    @Override
    public ChatExecutionContext withUserMessage(AiMessage.User message) {
        return copy(Objects.requireNonNull(message, "message"), toolsEnabled,
                streamVisibleContent, toolPolicy, agentDepth, approvalScope,
                approvalWaitLifecycle, agentId, executionPurpose, guardrailDecisionIds,
                workflowObservationContext, toolBinding);
    }

    @Override
    public ChatExecutionContext withRetryFeedback(String feedback) {
        if (!StringUtils.hasText(feedback)) return this;
        return withUserMessage(new AiMessage.User(userMessage.content()
                + "\n\nOUTPUT_POLICY_FEEDBACK\n" + feedback.strip(),
                userMessage.attachments()));
    }

    @Override
    public ChatExecutionContext withAgentIdentity(String identity,
                                                  ExecutionScope.Purpose purpose) {
        return copy(userMessage, toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                approvalScope, approvalWaitLifecycle, identity,
                Objects.requireNonNull(purpose, "purpose"), guardrailDecisionIds,
                workflowObservationContext, toolBinding);
    }

    @Override
    public ChatExecutionContext withGuardrailDecisions(List<String> decisionIds) {
        return copy(userMessage, toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                approvalScope, approvalWaitLifecycle, agentId, executionPurpose,
                decisionIds, workflowObservationContext, toolBinding);
    }

    @Override
    public ChatExecutionContext withWorkflowObservationContext(Map<String, Object> value) {
        return copy(userMessage, toolsEnabled, streamVisibleContent, toolPolicy, agentDepth,
                approvalScope, approvalWaitLifecycle, agentId, executionPurpose,
                guardrailDecisionIds, value, toolBinding);
    }

    @Override
    public ChatExecutionContext forWorkflowAssignment(AgentToolPolicy policy,
                                                      String assignedAgentId) {
        AgentToolPolicy reduced = Objects.requireNonNullElse(policy, AgentToolPolicy.NONE);
        return copy(userMessage, reduced != AgentToolPolicy.NONE, false, reduced, 1,
                approvalScope, approvalWaitLifecycle, assignedAgentId,
                ExecutionScope.Purpose.WORKER, guardrailDecisionIds,
                workflowObservationContext, toolBinding);
    }

    private ChatExecutionContext copy(AiMessage.User nextUserMessage,
                                      boolean nextToolsEnabled,
                                      boolean nextStreamVisibleContent,
                                      AgentToolPolicy nextToolPolicy,
                                      int nextAgentDepth,
                                      AiChangeApprovalScope nextApprovalScope,
                                      AgentApprovalWaitLifecycle nextApprovalWaitLifecycle,
                                      String nextAgentId,
                                      ExecutionScope.Purpose nextPurpose,
                                      List<String> nextGuardrailDecisionIds,
                                      Map<String, Object> nextWorkflowObservationContext,
                                      AgentToolBinding nextToolBinding) {
        return new ChatExecutionContext(request, history, nextUserMessage, requester, recorder,
                nextToolsEnabled, nextStreamVisibleContent, nextToolPolicy, nextAgentDepth,
                nextApprovalScope, nextApprovalWaitLifecycle, nextAgentId, nextPurpose,
                nextGuardrailDecisionIds, nextWorkflowObservationContext, nextToolBinding);
    }

    private static String normalizeAgentId(String value, AgentToolPolicy policy, int depth) {
        if (StringUtils.hasText(value)) return value.strip().toLowerCase(Locale.ROOT);
        if (depth > 0) return "worker-agent";
        return policy == AgentToolPolicy.NONE ? "internal-agent" : "unresolved-root-agent";
    }

    private static ExecutionScope.Purpose defaultPurpose(AgentToolPolicy policy, int depth) {
        if (depth > 0) return ExecutionScope.Purpose.WORKER;
        return policy == AgentToolPolicy.NONE
                ? ExecutionScope.Purpose.SYNTHESIS : ExecutionScope.Purpose.USER_RESPONSE;
    }
}

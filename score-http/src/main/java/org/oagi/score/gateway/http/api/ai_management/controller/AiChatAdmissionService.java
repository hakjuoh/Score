package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.policy.model.EffectiveAiPolicy;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowIntent;
import org.oagi.score.gateway.http.api.ai_management.agent.DelegationIntent;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;

/** Registers and prepares one request before either REST or WebSocket execution begins. */
final class AiChatAdmissionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatAdmissionService.class);
    private static final String MULTI_AGENT_DISABLED_NOTICE =
            "Multi-agent execution was requested but is disabled by your AI policy. "
                    + "This request will continue with the assistant only.";
    private static final String MULTI_AGENT_LIMITED_NOTICE =
            "The requested agent count exceeds your AI policy limit and was reduced.";

    private final ChatService chatService;
    private final AiRequestRegistry requests;
    private final ScoreAiObservability observability;
    private final Duration inactivityTimeout;

    AiChatAdmissionService(ChatService chatService, AiRequestRegistry requests,
                           ScoreAiObservability observability, Duration inactivityTimeout) {
        this.chatService = Objects.requireNonNull(chatService, "chatService");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.inactivityTimeout = Objects.requireNonNull(inactivityTimeout, "inactivityTimeout");
    }

    Admission prepare(ChatRequest request, ScoreUser requester,
                      String traceparent, String tracestate) {
        if (request == null) throw new IllegalArgumentException("Chat request must not be null.");
        String requestId = StringUtils.hasText(request.requestId())
                ? request.requestId() : UUID.randomUUID().toString();
        ChatRequest correlated = new ChatRequest(request.prompt(), requestId, request.agent(),
                request.conversationId(), request.pageContext(), request.attachments(),
                request.changeConfirmation(), request.modelName(), request.reasoningEffort(),
                request.permissionMode(), request.multiAgent(), request.activeWorkflow(),
                request.routeManifest());
        boolean requestedMultiAgent = requestsMultiAgent(correlated);
        int requestedAgentCount = AiWorkflowIntent.explicitlyRequestsAgents(correlated.prompt())
                ? DelegationIntent.requestedAgentCount(correlated.prompt()).orElse(
                org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions.MAX_AGENTS)
                : correlated.multiAgent().maxAgents();
        EffectiveAiPolicy policy = chatService.resolvePolicy(requester);
        if (policy != null) {
            policy.requireEnabled();
            String requestedModel = StringUtils.hasText(correlated.modelName())
                    ? correlated.modelName().strip() : policy.resolveDefaultModel();
            policy.requireModelAllowed(requestedModel);
            policy.requireReasoningEffortAllowed(requestedModel, correlated.reasoningEffort());
            correlated = new ChatRequest(correlated.prompt(), correlated.requestId(), correlated.agent(),
                    correlated.conversationId(), correlated.pageContext(), correlated.attachments(),
                    correlated.changeConfirmation(), requestedModel, correlated.reasoningEffort(),
                    correlated.permissionMode(), policy.constrain(correlated.multiAgent()),
                    policy.multiAgentEnabled() ? correlated.activeWorkflow() : "assistant",
                    correlated.routeManifest());
        }
        Instant deadline = Instant.now().plus(inactivityTimeout);
        AiRequestRegistry.Entry entry;
        try {
            entry = policy != null
                    ? requests.register(requestId, request.conversationId(), requester, deadline,
                    policy.maxActiveRequests())
                    : requests.register(requestId, request.conversationId(), requester, deadline);
            if (policy != null) chatService.snapshotPolicy(requestId, policy);
        } catch (RuntimeException failure) {
            observability.recordAdmissionRejection(correlated, requester, failure,
                    admissionReason(failure), traceparent, tracestate);
            throw failure;
        }
        try {
            ChatRequest prepared = policy != null
                    ? chatService.prepare(correlated, requester, entry.generation(),
                    policy.multiAgentEnabled())
                    : chatService.prepare(correlated, requester, entry.generation());
            boolean policyDisabledMultiAgent = policy != null && !policy.multiAgentEnabled()
                    && (requestedMultiAgent || requestsMultiAgent(prepared));
            if (policy != null) {
                policy.requireModelAllowed(prepared.modelName());
                policy.requireReasoningEffortAllowed(prepared.modelName(), prepared.reasoningEffort());
                var constrainedMultiAgent = policy.constrain(prepared.multiAgent());
                prepared = new ChatRequest(prepared.prompt(), prepared.requestId(), prepared.agent(),
                        prepared.conversationId(), prepared.pageContext(), prepared.attachments(),
                        prepared.changeConfirmation(), prepared.modelName(), prepared.reasoningEffort(),
                        prepared.permissionMode(), constrainedMultiAgent,
                        policy.multiAgentEnabled() && constrainedMultiAgent.active()
                                ? prepared.activeWorkflow() : "assistant",
                        prepared.routeManifest());
            }
            boolean policyLimitedMultiAgent = policy != null && policy.multiAgentEnabled()
                    && requestedMultiAgent
                    && effectiveAgentCount(prepared) < requestedAgentCount;
            if (policyDisabledMultiAgent || policyLimitedMultiAgent) {
                observability.multiAgentPolicyDowngrade();
            }
            requests.bindConversation(entry, prepared.conversationId());
            ScoreAiObservability.Turn observation = observability.startTurn(
                    prepared, requester, entry.generation(), traceparent, tracestate);
            AiExecutionEvent policyNotice = policyDisabledMultiAgent
                    ? AiExecutionEvent.detail("policy_notice", MULTI_AGENT_DISABLED_NOTICE,
                    Map.of("policyNotice", true, "code", "AI_MULTI_AGENT_DISABLED",
                            "requested", "agents", "effective", "assistant"))
                    : policyLimitedMultiAgent
                    ? AiExecutionEvent.detail("policy_notice", MULTI_AGENT_LIMITED_NOTICE,
                    Map.of("policyNotice", true, "code", "AI_MULTI_AGENT_LIMITED",
                            "requested", requestedAgentCount,
                            "effective", effectiveAgentCount(prepared))) : null;
            if (policyNotice != null) chatService.snapshotPolicyNotice(requestId, policyNotice);
            return new Admission(prepared, entry, deadline, observation,
                    policyNotice);
        } catch (RuntimeException | Error failure) {
            chatService.clearPolicySnapshot(requestId);
            settleRejected(entry, failure);
            observability.recordAdmissionRejection(correlated, requester, failure,
                    admissionReason(failure), traceparent, tracestate, entry.generation());
            throw failure;
        }
    }

    private boolean requestsMultiAgent(ChatRequest request) {
        if (request.multiAgent().active()) return true;
        if (AiWorkflowIntent.explicitlyRequestsAgents(request.prompt())) return true;
        return StringUtils.hasText(request.activeWorkflow())
                && !"assistant".equalsIgnoreCase(request.activeWorkflow().strip());
    }

    private int effectiveAgentCount(ChatRequest request) {
        return request.multiAgent().active() ? request.multiAgent().maxAgents() : 1;
    }

    private void settleRejected(AiRequestRegistry.Entry entry, Throwable failure) {
        try {
            requests.finish(entry, failure);
        } catch (RuntimeException | Error settlementFailure) {
            if (settlementFailure != failure) failure.addSuppressed(settlementFailure);
            LOGGER.error("Could not settle rejected AI request {}", entry.requestId(),
                    settlementFailure);
        }
    }

    private String admissionReason(Throwable failure) {
        if (failure instanceof java.util.concurrent.RejectedExecutionException) {
            return "executor_rejected";
        }
        String message = Objects.toString(failure != null ? failure.getMessage() : null, "")
                .toLowerCase(java.util.Locale.ROOT);
        if (message.contains("registry is at capacity")) return "registry_capacity";
        if (message.contains("too many ai requests")) return "user_limit";
        if (message.contains("already has an active request")) return "conversation_busy";
        if (message.contains("requestid already exists")) return "duplicate_request";
        if (failure instanceof IllegalArgumentException) return "validation";
        return "preparation_failed";
    }

    record Admission(ChatRequest request, AiRequestRegistry.Entry entry, Instant deadline,
                     ScoreAiObservability.Turn observation, AiExecutionEvent policyNotice) {
    }
}

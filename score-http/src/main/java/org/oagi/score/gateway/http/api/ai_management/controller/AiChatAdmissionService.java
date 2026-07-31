package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Registers and prepares one request before either REST or WebSocket execution begins. */
final class AiChatAdmissionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatAdmissionService.class);

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
        Instant deadline = Instant.now().plus(inactivityTimeout);
        AiRequestRegistry.Entry entry;
        try {
            entry = requests.register(requestId, request.conversationId(), requester, deadline);
        } catch (RuntimeException failure) {
            observability.recordAdmissionRejection(correlated, requester, failure,
                    admissionReason(failure), traceparent, tracestate);
            throw failure;
        }
        try {
            ChatRequest prepared = chatService.prepare(correlated, requester, entry.generation());
            requests.bindConversation(entry, prepared.conversationId());
            ScoreAiObservability.Turn observation = observability.startTurn(
                    prepared, requester, entry.generation(), traceparent, tracestate);
            return new Admission(prepared, entry, deadline, observation);
        } catch (RuntimeException | Error failure) {
            settleRejected(entry, failure);
            observability.recordAdmissionRejection(correlated, requester, failure,
                    admissionReason(failure), traceparent, tracestate, entry.generation());
            throw failure;
        }
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
                     ScoreAiObservability.Turn observation) {
    }
}

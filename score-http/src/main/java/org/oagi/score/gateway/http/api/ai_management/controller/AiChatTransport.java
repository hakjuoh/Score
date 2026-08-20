package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiCancellationResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChangeReadBackException;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Protocol-only projection and safe error mapping for REST and WebSocket chat transports. */
final class AiChatTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiChatTransport.class);
    private static final String GENERIC_FAILURE_MESSAGE =
            "The assistant could not finish this request. Some steps may have completed before "
                    + "it stopped. Review the conversation and affected records, then retry only "
                    + "the unfinished part. If the problem continues, contact an administrator "
                    + "with the conversation and approximate failure time.";
    private static final Set<String> WORKFLOW_LIFECYCLE_EVENT_TYPES = Set.of(
            "workflow_started", "workflow_completed", "workflow_failed", "workflow_cancelled",
            "workflow_refused", "workflow_stalled", "workflow_output_retry_handoff",
            "subagent_preparing", "subagent_planned", "subagent_started", "subagent_retry",
            "subagent_output_retry_handoff", "subagent_completed", "subagent_failed",
            "subagent_cancelled", "subagent_refused");

    private AiChatTransport() {
    }

    static AiChatSocketEvent socketEvent(ChatRequest request, long sequence, AiExecutionEvent event) {
        if ("assistant_update".equals(event.type())) {
            return AiChatSocketEvent.assistantUpdate(request.requestId(), request.conversationId(),
                    sequence, event.content());
        }
        if ("tool_call".equals(event.type())) {
            return AiChatSocketEvent.toolCall(request.requestId(), request.conversationId(), sequence,
                    event.subtype(), event.content(), event.toolCallId(), event.toolName(),
                    event.toolCallSequence() != null ? event.toolCallSequence() : sequence,
                    event.metadata());
        }
        if ("detail".equals(event.type())) {
            if ("change_approval_batch_required".equals(event.subtype())) {
                return system(request, sequence, event);
            }
            if ("change_confirmation_required".equals(event.subtype())) {
                return AiChatSocketEvent.changeConfirmationRequired(request.requestId(),
                        request.conversationId(), sequence, event.content(), event.metadata());
            }
            if ("elicitation_required".equals(event.subtype())) {
                return AiChatSocketEvent.elicitationRequired(request.requestId(),
                        request.conversationId(), sequence, event.content(), event.metadata());
            }
            if ("guide".equals(event.subtype()) || "policy_notice".equals(event.subtype())
                    || "workflow_result".equals(event.subtype())
                    || isWorkflowLifecycleEvent(event.subtype())) {
                return system(request, sequence, event);
            }
            return AiChatSocketEvent.detail(request.requestId(), request.conversationId(), sequence,
                    event.subtype(), event.content(), event.metadata());
        }
        return AiChatSocketEvent.progress(request.requestId(), request.conversationId(),
                sequence, event.content());
    }

    static boolean isRestResponseEvent(AiExecutionEvent event) {
        return "tool_call".equals(event.type()) || "detail".equals(event.type())
                && ("change_confirmation_required".equals(event.subtype())
                || "change_approval_batch_required".equals(event.subtype())
                || "change_approval_decision_accepted".equals(event.subtype())
                || "elicitation_required".equals(event.subtype())
                || "context_usage".equals(event.subtype())
                || "context_compacted".equals(event.subtype())
                || "provider_error".equals(event.subtype())
                || "provider_retry".equals(event.subtype())
                || "workflow_result".equals(event.subtype())
                || "guide".equals(event.subtype())
                || "policy_notice".equals(event.subtype())
                || isWorkflowLifecycleEvent(event.subtype()));
    }

    static boolean isRestLiveEvent(AiExecutionEvent event) {
        return "tool_call".equals(event.type())
                || "guide".equals(event.subtype())
                || "policy_notice".equals(event.subtype())
                || "change_approval_batch_required".equals(event.subtype())
                || "change_approval_decision_accepted".equals(event.subtype())
                || "elicitation_required".equals(event.subtype())
                || "provider_error".equals(event.subtype())
                || "provider_retry".equals(event.subtype())
                || "workflow_result".equals(event.subtype())
                || isWorkflowLifecycleEvent(event.subtype());
    }

    static List<AiChatSocketEvent> orderedResponseEvents(List<AiChatSocketEvent> events) {
        return events.stream().sorted(Comparator.comparing(AiChatSocketEvent::sequence,
                Comparator.nullsLast(Long::compareTo))).toList();
    }

    static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return GENERIC_FAILURE_MESSAGE;
        }
        var provider = findCause(throwable,
                org.oagi.score.gateway.http.api.ai_management.provider.AiProviderException.class);
        if (provider != null) {
            LOGGER.warn("AI chat request failed at the model provider", provider);
            return provider.getMessage();
        }
        AiChangeReadBackException readBack = findCause(
                throwable, AiChangeReadBackException.class);
        if (readBack != null) {
            LOGGER.warn("AI chat request ended without post-change verification", readBack);
            return readBackMessage(readBack.completedChangeCount());
        }
        Throwable current = rootCause(throwable);
        LOGGER.warn("AI chat request failed", current);
        if (current instanceof IllegalArgumentException && StringUtils.hasText(current.getMessage())) {
            String message = current.getMessage();
            if (message.length() <= 500 && message.matches(
                    "(?i)^(attachment|attachments|unsupported ai attachment|a prompt|a maximum|the requested assistant model|the requested reasoning effort|chat request|multiagent).*$")) {
                return message;
            }
        }
        if (current instanceof java.util.concurrent.TimeoutException) {
            return "The assistant did not report further progress before the operation timed out, "
                    + "so it was stopped. Any changes already reported as completed remain "
                    + "applied. Review the conversation and affected records, then retry only "
                    + "the unfinished part.";
        }
        String failureClass = current.getClass().getName();
        if (failureClass.startsWith("io.modelcontextprotocol.")) {
            return failureClass.contains("Authorization")
                    ? "The assistant could not authorize with the connectCenter tool service."
                    + " Data changes that already completed remain applied. Please retry."
                    : "The assistant's connection to the connectCenter tool service failed."
                    + " Data changes that already completed remain applied. Please retry.";
        }
        if (failureClass.startsWith("org.springframework.ai.retry.")
                || failureClass.startsWith("org.springframework.web.reactive.function.client.")
                || failureClass.startsWith("org.springframework.web.client.")) {
            return "The assistant's model provider could not complete the request. Please retry.";
        }
        return GENERIC_FAILURE_MESSAGE;
    }

    static String failureClass(Throwable throwable) {
        return throwable != null ? rootCause(throwable).getClass().getName() : null;
    }

    static String terminalMessage(String status, Throwable throwable) {
        if ("TIMED_OUT".equals(status)) {
            if (throwable != null) {
                LOGGER.warn("AI chat request stopped after its inactivity lease expired", throwable);
            }
            return "No further assistant activity was received before the inactivity timeout, so "
                    + "the request was stopped. Any changes already reported as completed remain "
                    + "applied. Review the conversation and affected records, then retry only "
                    + "the unfinished part.";
        }
        return safeMessage(throwable);
    }

    static HttpStatus restTerminalStatus(String status) {
        return switch (status) {
            case "TIMED_OUT" -> HttpStatus.REQUEST_TIMEOUT;
            case "FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
    }

    static RuntimeException propagate(Throwable throwable, String status) {
        if (throwable instanceof java.util.concurrent.CompletionException completionException) {
            return completionException;
        }
        if (throwable instanceof RuntimeException runtimeException) return runtimeException;
        if (throwable != null) return new java.util.concurrent.CompletionException(throwable);
        return new IllegalStateException("The AI request ended with status " + status + ".");
    }

    static String cancellationDispositionMessage(AiCancellationResponse response) {
        return switch (response.disposition()) {
            case "STALE_GENERATION" -> "Cancellation rejected because the request identity is stale.";
            case "ALREADY_TERMINAL" -> "The request was already terminal.";
            case "CANCELLED" -> "Request cancelled before execution started.";
            default -> "Cancellation status updated.";
        };
    }

    private static AiChatSocketEvent system(ChatRequest request, long sequence,
                                            AiExecutionEvent event) {
        return AiChatSocketEvent.system(request.requestId(), request.conversationId(), sequence,
                event.subtype(), event.content(), event.metadata());
    }

    private static boolean isWorkflowLifecycleEvent(String subtype) {
        return WORKFLOW_LIFECYCLE_EVENT_TYPES.contains(subtype);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String readBackMessage(int completedChangeCount) {
        String operations = completedChangeCount == 1
                ? "1 change operation" : completedChangeCount + " change operations";
        return "The assistant completed " + operations
                + " but could not verify the final state. Any changes that succeeded remain "
                + "applied. Refresh or inspect the affected records, then ask the assistant to "
                + "verify them and complete any remaining work.";
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for (Throwable candidate = throwable; candidate != null; candidate = candidate.getCause()) {
            if (type.isInstance(candidate)) return type.cast(candidate);
        }
        return null;
    }
}

package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatResponse;
import org.oagi.score.gateway.http.api.ai_management.service.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.oagi.score.gateway.http.configuration.websocket.WebSocketSessionUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.AuthenticatedPrincipal;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatControllerTest {

    private final ScoreUser user = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
            null, false, List.of());
    private final AuthenticatedPrincipal principal = mock(AuthenticatedPrincipal.class);
    private final ChatService chatService = mock(ChatService.class);
    private final SessionService sessionService = mock(SessionService.class);

    @Test
    void returnsMutationConfirmationEventsFromTheRestTransport() throws Exception {
        AiChatController controller = controller(new AiRequestRegistry(), new ScoreAiProperties(), Runnable::run);
        ChatRequest request = request("request-1", "conversation-1");
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(any(ChatRequest.class), eq(user), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AiExecutionEvent> events = invocation.getArgument(2);
            events.accept(AiExecutionEvent.progress("internal progress"));
            events.accept(AiExecutionEvent.detail("mutation_confirmation_required",
                    "A data-changing action requires explicit approval.", Map.of(
                            "confirmationRequestId", "confirmation-1",
                            "status", "REQUESTED",
                            "expiresAt", "2099-07-15T00:00:00Z",
                            "toolName", "update_business_context",
                            "argumentsSummary", "{\"id\":1}")));
            return new ChatResponse("connectcenter-assistant", "Approval is required.",
                    "conversation-1", false, List.of());
        });

        ChatResponse response = controller.chat(principal, request).get(1, TimeUnit.SECONDS).getBody();

        assertThat(response).isNotNull();
        assertThat(response.events()).singleElement().satisfies(event -> {
            assertThat(event.requestId()).isEqualTo("request-1");
            assertThat(event.conversationId()).isEqualTo("conversation-1");
            assertThat(event.subtype()).isEqualTo("mutation_confirmation_required");
            assertThat(event.metadata()).containsEntry("confirmationRequestId", "confirmation-1");
        });
    }

    @Test
    void recordsTimedOutRestRequestsInTheTrajectory() throws Exception {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setRequestTimeout(Duration.ofMillis(25));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AiChatController controller = controller(new AiRequestRegistry(), properties, executor);
            ChatRequest request = request("request-timeout", "conversation-timeout");
            when(sessionService.asScoreUser(principal)).thenReturn(user);
            when(chatService.prepare(any(ChatRequest.class), eq(user)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(chatService.chat(any(ChatRequest.class), eq(user), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Consumer<AiExecutionEvent> events = invocation.getArgument(2);
                events.accept(mutationNotice());
                try {
                    Thread.sleep(60_000);
                    return new ChatResponse("agent", "late", "conversation-timeout", false, List.of());
                } catch (InterruptedException expected) {
                    throw new CancellationException("deadline interrupted the worker");
                }
            });

            ResponseEntity<ChatResponse> response = controller.chat(principal, request)
                    .get(1, TimeUnit.SECONDS);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().events()).singleElement()
                    .extracting(event -> event.metadata().get("confirmationRequestId"))
                    .isEqualTo("confirmation-1");
            verify(chatService, timeout(1_000)).recordFailure(any(ChatRequest.class), eq(user),
                    eq("The assistant request deadline was exceeded."));
        }
    }

    @Test
    void reservesAnExistingConversationBeforePreparationSideEffects() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiChatController controller = controller(registry, new ScoreAiProperties(), Runnable::run);
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch releasePreparation = new CountDownLatch(1);
        when(sessionService.asScoreUser(principal)).thenReturn(user);
        when(chatService.prepare(any(ChatRequest.class), eq(user))).thenAnswer(invocation -> {
            preparing.countDown();
            if (!releasePreparation.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test preparation was not released");
            }
            return invocation.getArgument(0);
        });
        when(chatService.chat(any(ChatRequest.class), eq(user), any())).thenReturn(
                new ChatResponse("agent", "done", "conversation-1", false, List.of()));

        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<ResponseEntity<ChatResponse>> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return controller.chat(principal, request("request-1", "conversation-1")).get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }, caller);
            assertThat(preparing.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> controller.chat(
                    principal, request("request-2", "conversation-1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active request");

            releasePreparation.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(chatService, times(1)).prepare(any(ChatRequest.class), eq(user));
        } finally {
            releasePreparation.countDown();
        }
    }

    private AiChatController controller(AiRequestRegistry registry, ScoreAiProperties properties,
                                        java.util.concurrent.Executor executor) {
        return new AiChatController(chatService, sessionService, mock(SimpMessagingTemplate.class),
                mock(WebSocketSessionUserResolver.class), registry,
                mock(AiMutationConfirmationService.class), properties, executor);
    }

    private ChatRequest request(String requestId, String conversationId) {
        return new ChatRequest("Help me", requestId, null, conversationId,
                null, List.of(), null);
    }

    private AiExecutionEvent mutationNotice() {
        return AiExecutionEvent.detail("mutation_confirmation_required",
                "A data-changing action requires explicit approval.", Map.of(
                        "confirmationRequestId", "confirmation-1",
                        "status", "REQUESTED",
                        "expiresAt", "2099-07-15T00:00:00Z",
                        "toolName", "update_business_context",
                        "argumentsSummary", "{\"id\":1}"));
    }
}

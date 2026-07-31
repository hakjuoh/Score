package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiConversationRestoreRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatHistoryMessage;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatInteractionHandlerTest {

    @Test
    void restoresConversationEventsInProtocolOrder() {
        ChatService chatService = mock(ChatService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ScoreUser requester = mock(ScoreUser.class);
        when(requester.username()).thenReturn("alice");
        ChatHistoryMessage message = new ChatHistoryMessage(1, "user", "hello",
                "request-0", "turn-0", null, null, null, null, "visible", Map.of());
        when(chatService.conversation(requester, "conversation-1")).thenReturn(
                new ChatConversationDetails("conversation-1", "Title", "model", "medium",
                        Instant.EPOCH, List.of(message), List.of(), null, "ask", "agents"));
        AiChatInteractionHandler handler = new AiChatInteractionHandler(chatService,
                mock(AiRequestRegistry.class), mock(AiElicitationService.class), null,
                ExecutionObserver.noop(), messaging);

        handler.restore(requester,
                new AiConversationRestoreRequest("request-1", "conversation-1", "token", 4L));

        ArgumentCaptor<AiChatSocketEvent> events = ArgumentCaptor.forClass(AiChatSocketEvent.class);
        verify(messaging, org.mockito.Mockito.times(4)).convertAndSendToUser(
                eq("alice"), eq("/queue/ai/chat/request-1"), events.capture());
        assertThat(events.getAllValues()).extracting(AiChatSocketEvent::type)
                .containsExactly("system", "HISTORY_START", "HISTORY_MESSAGE", "HISTORY_FINAL");
        assertThat(events.getAllValues().getFirst().subtype()).isEqualTo("accepted");
    }
}

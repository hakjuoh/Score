package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatSocketEventTest {

    @Test
    void acceptedEventCarriesTheConversationFenceExpectedByTheUi() {
        Instant deadline = Instant.parse("2026-07-16T12:00:00Z");

        AiChatSocketEvent event = AiChatSocketEvent.accepted(
                "request-1", "conversation-1", 1L, deadline);

        assertThat(event.type()).isEqualTo("system");
        assertThat(event.subtype()).isEqualTo("accepted");
        assertThat(event.metadata()).containsEntry("generation", 1L)
                .containsEntry("deadline", deadline.toString());
    }

    @Test
    void mutationNoticeSerializesOnlyItsStableSecurityEnvelope() throws Exception {
        AiChatSocketEvent event = AiChatSocketEvent.mutationConfirmationRequired(
                "request-1", "conversation-1", 4L,
                "A data-changing action requires explicit approval.", Map.of(
                        "confirmationRequestId", "confirmation-1",
                        "status", "REQUESTED",
                        "expiresAt", "2099-07-15T00:00:00Z",
                        "toolName", "delete_business_context",
                        "argumentsSummary", "{\"id\":1}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> serialized = new ObjectMapper().readValue(
                new ObjectMapper().writeValueAsBytes(event), Map.class);

        assertThat(serialized.keySet()).isEqualTo(Set.of(
                "requestId", "conversationId", "type", "message", "turnId",
                "sequence", "subtype", "visibility", "content", "metadata"));
    }
}

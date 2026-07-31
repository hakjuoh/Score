package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatSocketEvent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatTransportPolicyNoticeTest {

    @Test
    void projectsPolicyNoticeAsVisibleSystemEventForBothTransports() {
        ChatRequest request = new ChatRequest("hello", "request-1", null, "conversation-1",
                null, List.of(), null);
        AiExecutionEvent notice = AiExecutionEvent.detail("policy_notice", "Policy constrained it.",
                Map.of("code", "AI_MULTI_AGENT_DISABLED"));

        AiChatSocketEvent projected = AiChatTransport.socketEvent(request, 1L, notice);

        assertThat(projected.type()).isEqualTo("system");
        assertThat(projected.subtype()).isEqualTo("policy_notice");
        assertThat(projected.metadata()).containsEntry("code", "AI_MULTI_AGENT_DISABLED");
        assertThat(AiChatTransport.isRestResponseEvent(notice)).isTrue();
        assertThat(AiChatTransport.isRestLiveEvent(notice)).isTrue();
    }
}

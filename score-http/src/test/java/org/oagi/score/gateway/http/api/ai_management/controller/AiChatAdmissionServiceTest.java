package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.service.ChatService;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatAdmissionServiceTest {

    @Test
    void settlesPreparationFailureAndPreservesSettlementFailureAsSuppressed() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        when(entry.requestId()).thenReturn("request-1");
        when(entry.generation()).thenReturn(3L);
        when(requests.register(eq("request-1"), eq(null), eq(requester), any(Instant.class)))
                .thenReturn(entry);
        IllegalArgumentException failure = new IllegalArgumentException("invalid request");
        IllegalStateException settlement = new IllegalStateException("settlement failed");
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(3L))).thenThrow(failure);
        when(requests.finish(entry, failure)).thenThrow(settlement);
        AiChatAdmissionService admissions = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1));

        assertThatThrownBy(() -> admissions.prepare(request(), requester, "parent", "state"))
                .isSameAs(failure);

        assertThat(failure.getSuppressed()).containsExactly(settlement);
        verify(observability).recordAdmissionRejection(any(ChatRequest.class), eq(requester),
                eq(failure), eq("validation"), eq("parent"), eq("state"), eq(3L));
    }

    private ChatRequest request() {
        return new ChatRequest("hello", "request-1", null, null,
                null, List.of(), null, null, null, "ask");
    }
}

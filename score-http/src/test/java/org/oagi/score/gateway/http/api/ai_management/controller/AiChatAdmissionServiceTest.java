package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.policy.model.EffectiveAiPolicy;
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
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(3L)))
                .thenThrow(failure);
        when(requests.finish(entry, failure)).thenThrow(settlement);
        AiChatAdmissionService admissions = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1));

        assertThatThrownBy(() -> admissions.prepare(request(), requester, "parent", "state"))
                .isSameAs(failure);

        assertThat(failure.getSuppressed()).containsExactly(settlement);
        verify(observability).recordAdmissionRejection(any(ChatRequest.class), eq(requester),
                eq(failure), eq("validation"), eq("parent"), eq("state"), eq(3L));
    }

    @Test
    void reportsWhenPolicyConstrainsRequestedMultiAgentExecution() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreAiObservability.Turn turn = mock(ScoreAiObservability.Turn.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        ChatRequest requested = new ChatRequest("compare approaches", "request-2", null, null,
                null, List.of(), null, "model", null, "ask",
                new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(
                        true, 3, "balanced"), "verification", null);
        ChatRequest prepared = requested.withMultiAgent(
                org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions.single())
                .withActiveWorkflow("assistant");
        when(policy.multiAgentEnabled()).thenReturn(false);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(
                org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions.single());
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(4L);
        when(requests.register(eq("request-2"), eq(null), eq(requester), any(Instant.class), eq(8)))
                .thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(4L), eq(false)))
                .thenReturn(prepared);
        when(observability.startTurn(any(), eq(requester), eq(4L), eq(null), eq(null)))
                .thenReturn(turn);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.request().multiAgent().active()).isFalse();
        assertThat(admission.request().activeWorkflow()).isEqualTo("assistant");
        assertThat(admission.policyNotice().content()).contains("disabled by your AI policy");
        assertThat(admission.policyNotice().metadata()).containsEntry("policyNotice", true)
                .containsEntry("requested", "agents").containsEntry("effective", "assistant");
        verify(chatService).prepare(any(ChatRequest.class), eq(requester), eq(4L), eq(false));
    }

    @Test
    void detectsAnExplicitAgentPromptBeforeDisabledPolicySkipsIntentResolution() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        ChatRequest requested = new ChatRequest("Spawn exactly 3 sub-agents in parallel.",
                "request-3", null, null, null, List.of(), null, "model", null, "ask");
        when(policy.multiAgentEnabled()).thenReturn(false);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(
                org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions.single());
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(5L);
        when(requests.register(eq("request-3"), eq(null), eq(requester), any(Instant.class), eq(8)))
                .thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(5L), eq(false)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.policyNotice()).isNotNull();
        verify(chatService).snapshotPolicyNotice(eq("request-3"), eq(admission.policyNotice()));
    }

    @Test
    void usesTheExplicitAgentCountAndDoesNotReportAClampAtTheExactLimit() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        var three = new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(true, 3, "balanced");
        ChatRequest requested = new ChatRequest("Spawn exactly 3 agents in parallel.", "request-4",
                null, null, null, List.of(), null, "model", null, "ask");
        ChatRequest prepared = requested.withMultiAgent(three).withActiveWorkflow("verification");
        when(policy.multiAgentEnabled()).thenReturn(true);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(6L);
        when(requests.register(eq("request-4"), eq(null), eq(requester), any(Instant.class), eq(8))).thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(6L), eq(true))).thenReturn(prepared);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.policyNotice()).isNull();
    }

    @Test
    void reportsAClampWhenPolicyReducesAnExplicitRequestToOneAgent() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        var single = org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions.single();
        var four = new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(true, 4, "balanced");
        ChatRequest requested = new ChatRequest("Spawn exactly 3 agents in parallel.", "request-5",
                null, null, null, List.of(), null, "model", null, "ask");
        ChatRequest prepared = requested.withMultiAgent(four).withActiveWorkflow("agents");
        when(policy.multiAgentEnabled()).thenReturn(true);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(single);
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(7L);
        when(requests.register(eq("request-5"), eq(null), eq(requester), any(Instant.class), eq(8))).thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(7L), eq(true))).thenReturn(prepared);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.policyNotice().metadata()).containsEntry("requested", 3).containsEntry("effective", 1);
        assertThat(admission.request().activeWorkflow()).isEqualTo("assistant");
    }

    private ChatRequest request() {
        return new ChatRequest("hello", "request-1", null, null,
                null, List.of(), null, null, null, "ask");
    }
}

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
        when(policy.allowsMultiAgentRouting()).thenReturn(true);
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
    void preservesAutomaticWorkflowWhenPolicyAllowsDynamicRouting() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        var automatic = new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(
                false, 2, "balanced");
        ChatRequest requested = new ChatRequest("Create and profile related records.",
                "request-auto", null, null, null, List.of(), null, "model", null, "ask");
        ChatRequest prepared = requested.withMultiAgent(automatic);
        when(policy.multiAgentEnabled()).thenReturn(true);
        when(policy.allowsMultiAgentRouting()).thenReturn(true);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(automatic);
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(8L);
        when(requests.register(eq("request-auto"), eq(null), eq(requester),
                any(Instant.class), eq(8))).thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(8L), eq(true)))
                .thenReturn(prepared);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.request().activeWorkflow()).isNull();
        assertThat(admission.request().multiAgent()).isEqualTo(automatic);
    }

    @Test
    void reportsAClampAndContinuesWhenPolicyReducesFourRequestedAgentsToTwo() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        var two = new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(true, 2, "balanced");
        var four = new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(true, 4, "balanced");
        ChatRequest requested = new ChatRequest("Spawn exactly 4 agents in parallel.", "request-5",
                null, null, null, List.of(), null, "model", null, "ask");
        ChatRequest prepared = requested.withMultiAgent(four).withActiveWorkflow("agents");
        when(policy.multiAgentEnabled()).thenReturn(true);
        when(policy.allowsMultiAgentRouting()).thenReturn(true);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(two);
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(7L);
        when(requests.register(eq("request-5"), eq(null), eq(requester), any(Instant.class), eq(8))).thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(7L), eq(true))).thenReturn(prepared);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.policyNotice().content())
                .isEqualTo("You requested 4 agents, but your AI policy allows up to 2. "
                        + "I’ll continue this request with 2 agents.");
        assertThat(admission.policyNotice().metadata())
                .containsEntry("requested", 4)
                .containsEntry("effective", 2)
                .containsEntry("guideMessage", true);
        assertThat(admission.request().activeWorkflow()).isEqualTo("agents");
        assertThat(admission.request().multiAgent()).isEqualTo(two);
    }

    @Test
    void explicitAgentNegationDoesNotProduceAFalsePolicyClampGuide() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        var four = new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(true, 4, "balanced");
        var single = org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions.single();
        ChatRequest requested = new ChatRequest(
                "Do not use agents for this request.", "request-negated", null, null,
                null, List.of(), null, "model", null, "ask", four, "agents", null);
        ChatRequest prepared = requested.withMultiAgent(single).withActiveWorkflow("assistant");
        when(policy.multiAgentEnabled()).thenReturn(true);
        when(policy.allowsMultiAgentRouting()).thenReturn(true);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(single);
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(9L);
        when(requests.register(eq("request-negated"), eq(null), eq(requester),
                any(Instant.class), eq(8))).thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(9L), eq(true)))
                .thenReturn(prepared);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.policyNotice()).isNull();
        assertThat(admission.request().activeWorkflow()).isEqualTo("assistant");
    }

    @Test
    void explicitAgentNegationDoesNotProduceADisabledPolicyGuide() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        var four = new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(true, 4, "balanced");
        var single = org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions.single();
        ChatRequest requested = new ChatRequest(
                "Without agents, inspect this directly.", "request-disabled-negated", null,
                null, null, List.of(), null, "model", null, "ask", four, "agents", null);
        ChatRequest prepared = requested.withMultiAgent(single).withActiveWorkflow("assistant");
        when(policy.multiAgentEnabled()).thenReturn(false);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(single);
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(10L);
        when(requests.register(eq("request-disabled-negated"), eq(null), eq(requester),
                any(Instant.class), eq(8))).thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(10L), eq(false)))
                .thenReturn(prepared);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.policyNotice()).isNull();
        assertThat(admission.request().activeWorkflow()).isEqualTo("assistant");
    }

    @Test
    void oneAgentPolicyUsesTheAssistantOnlyGuide() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        var single = org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions.single();
        ChatRequest requested = new ChatRequest(
                "Spawn exactly 4 agents in parallel.", "request-one-agent", null, null,
                null, List.of(), null, "model", null, "ask");
        ChatRequest prepared = requested.withMultiAgent(single).withActiveWorkflow("assistant");
        when(policy.multiAgentEnabled()).thenReturn(true);
        when(policy.allowsMultiAgentRouting()).thenReturn(false);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(single);
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(11L);
        when(requests.register(eq("request-one-agent"), eq(null), eq(requester),
                any(Instant.class), eq(8))).thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(11L), eq(true)))
                .thenReturn(prepared);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.policyNotice().content()).contains("assistant only");
        assertThat(admission.policyNotice().metadata())
                .containsEntry("effective", "assistant");
        assertThat(admission.request().activeWorkflow()).isEqualTo("assistant");
    }

    @Test
    void unnumberedDelegationUsesTheSharedTwoAgentDefaultWithoutAClampGuide() {
        ChatService chatService = mock(ChatService.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiObservability observability = mock(ScoreAiObservability.class);
        ScoreUser requester = mock(ScoreUser.class);
        EffectiveAiPolicy policy = mock(EffectiveAiPolicy.class);
        AiRequestRegistry.Entry entry = mock(AiRequestRegistry.Entry.class);
        var two = new org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMultiAgentOptions(true, 2, "balanced");
        ChatRequest requested = new ChatRequest(
                "Use sub-agents to inspect this.", "request-default-agents", null, null,
                null, List.of(), null, "model", null, "ask");
        ChatRequest prepared = requested.withMultiAgent(two).withActiveWorkflow("agents");
        when(policy.multiAgentEnabled()).thenReturn(true);
        when(policy.allowsMultiAgentRouting()).thenReturn(true);
        when(policy.maxActiveRequests()).thenReturn(8);
        when(policy.constrain(any())).thenReturn(two);
        when(chatService.resolvePolicy(requester)).thenReturn(policy);
        when(entry.generation()).thenReturn(12L);
        when(requests.register(eq("request-default-agents"), eq(null), eq(requester),
                any(Instant.class), eq(8))).thenReturn(entry);
        when(chatService.prepare(any(ChatRequest.class), eq(requester), eq(12L), eq(true)))
                .thenReturn(prepared);

        AiChatAdmissionService.Admission admission = new AiChatAdmissionService(
                chatService, requests, observability, Duration.ofMinutes(1))
                .prepare(requested, requester, null, null);

        assertThat(admission.policyNotice()).isNull();
        assertThat(admission.request().multiAgent()).isEqualTo(two);
    }

    private ChatRequest request() {
        return new ChatRequest("hello", "request-1", null, null,
                null, List.of(), null, null, null, "ask");
    }
}

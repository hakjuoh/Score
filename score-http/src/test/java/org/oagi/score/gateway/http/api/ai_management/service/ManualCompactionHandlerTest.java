package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatResponse;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualCompactionHandlerTest {

    @Test
    void commitRejectionPublishesNoCompactionWritesAndAlwaysSealsTheRecorder() {
        Fixture fixture = new Fixture();
        when(fixture.compactions.executeSummary(any(), any(), any(), any(), any(),
                eq(true), any())).thenReturn(new AgentOutput("raw summary"));
        when(fixture.responses.finalizeOutput(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(fixture.finalized);
        doThrow(new CancellationException("cancelled")).when(fixture.results)
                .commit(eq("request-1"), any());

        assertThatThrownBy(() -> fixture.handler.handle(fixture.command()))
                .isInstanceOf(CancellationException.class);

        verify(fixture.compactions, never()).replaceMemoryWithSummary(any(), any(), any());
        verify(fixture.journal, never()).recordCompaction(
                any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class),
                any(Long.class));
        verify(fixture.recorder, never()).recordAssistantMessage(any(), any());
        verify(fixture.recorder, never()).contextCompacted(any(), any(Long.class), any(),
                any(Boolean.class));
        verify(fixture.recorder).sealAgainstLateCallbacks();
    }

    @Test
    void summaryFailureStillSealsTheRecorder() {
        Fixture fixture = new Fixture();
        when(fixture.compactions.executeSummary(any(), any(), any(), any(), any(),
                eq(true), any())).thenThrow(new IllegalStateException("provider failed"));

        assertThatThrownBy(() -> fixture.handler.handle(fixture.command()))
                .isInstanceOf(IllegalStateException.class).hasMessage("provider failed");

        verify(fixture.results, never()).commit(any(), any());
        verify(fixture.recorder).sealAgainstLateCallbacks();
    }

    @Test
    void cancellationBeforeDisclosureCommitsNothingAndStillSealsTheRecorder() {
        Fixture fixture = new Fixture();
        when(fixture.compactions.executeSummary(any(), any(), any(), any(), any(),
                eq(true), any())).thenReturn(new AgentOutput("raw summary"));
        when(fixture.responses.finalizeOutput(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new CancellationException("discarded"));

        assertThatThrownBy(() -> fixture.handler.handle(fixture.command()))
                .isInstanceOf(CancellationException.class);

        verify(fixture.results, never()).commit(any(), any());
        verify(fixture.compactions, never()).replaceMemoryWithSummary(any(), any(), any());
        verify(fixture.journal, never()).recordCompaction(
                any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class),
                any(Long.class));
        verify(fixture.recorder).sealAgainstLateCallbacks();
    }

    @Test
    void persistsAndPublishesOnlyTheFinalSafeSummaryAfterCommit() {
        Fixture fixture = new Fixture();
        when(fixture.compactions.executeSummary(any(), any(), any(), any(), any(),
                eq(true), any())).thenReturn(new AgentOutput("raw summary"));
        when(fixture.responses.finalizeOutput(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(fixture.finalized);
        ChatResponse response = new ChatResponse(
                "agent", "safe summary", "conversation-1", false, List.of());
        when(fixture.responses.response(any(), any(), any(), any(), any()))
                .thenReturn(response);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(fixture.results).commit(eq("request-1"), any());

        assertThat(fixture.handler.handle(fixture.command())).isSameAs(response);
        assertThat(fixture.progressEvents)
                .containsExactly("Compacting the conversation context.");

        verify(fixture.compactions).replaceMemoryWithSummary(
                fixture.requester, "conversation-1", "safe summary");
        verify(fixture.journal).recordCompaction(eq(fixture.requester), eq(fixture.request),
                eq(120L), eq(0L), eq("safe summary"), eq(false), eq(7L));
        verify(fixture.recorder).recordAssistantMessage(eq("safe summary"), any());
        verify(fixture.recorder).contextCompacted("manual", 120L, null, false);
        verify(fixture.recorder).sealAgainstLateCallbacks();
    }

    private static final class Fixture {

        private final ConversationCompactionSupport compactions =
                mock(ConversationCompactionSupport.class);
        private final AiContextBudgetService contextBudgets =
                mock(AiContextBudgetService.class);
        private final ChatConversationJournal journal = mock(ChatConversationJournal.class);
        private final ChatResultCommitter results = mock(ChatResultCommitter.class);
        private final ChatResponseFinalizer responses = mock(ChatResponseFinalizer.class);
        private final AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        private final ScoreUser requester = mock(ScoreUser.class);
        private final ChatRequest request = new ChatRequest(
                ChatCommands.compactPrompt(), "request-1", null, "conversation-1",
                null, List.of(), null, "model", "medium", "ask");
        private final UserMessage compactMessage = new UserMessage("compact safely");
        private final ChatResponseFinalizer.FinalizedOutput finalized =
                new ChatResponseFinalizer.FinalizedOutput(
                        "agent", "safe summary", Map.of("agent_id", "agent"));
        private final ManualCompactionHandler handler = new ManualCompactionHandler(
                compactions, contextBudgets, journal, results, responses);
        private final List<String> progressEvents = new ArrayList<>();

        private ManualCompactionHandler.Command command() {
            return new ManualCompactionHandler.Command(
                    request, requester, List.of(new UserMessage("history")), compactMessage,
                    mock(ExecutionScope.class), Optional.empty(), 120L, Map.of(), "ask",
                    recorder, progressEvents::add, new ArrayList<>(), 7L);
        }
    }
}

package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AtifTrajectoryService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConversationUseCasesTest {

    @Test
    void treatsAnUnavailableRepositoryAsMissingUsage() {
        AiConversationUseCases conversations = new AiConversationUseCases(
                mock(ScoreAiModelRegistry.class), requester -> null, requester -> null,
                mock(AiContextBudgetService.class), mock(AtifTrajectoryService.class),
                null, null);

        assertThat(conversations.latestUsage(null, "conversation-1")).isEmpty();
    }

    @Test
    void deletesFilesThenClearsMemoryAndToolSessionAfterRepositorySuccess() {
        ScoreUser requester = mock(ScoreUser.class);
        AiChatConversationRepository repository = mock(AiChatConversationRepository.class);
        ChatMemory memory = mock(ChatMemory.class);
        AiFileService files = mock(AiFileService.class);
        ToolSearchToolCallingAdvisor toolSessions = mock(ToolSearchToolCallingAdvisor.class);
        when(repository.delete("conversation-1")).thenReturn(true);
        AiConversationUseCases conversations = new AiConversationUseCases(
                mock(ScoreAiModelRegistry.class), ignored -> memory, ignored -> repository,
                mock(AiContextBudgetService.class), mock(AtifTrajectoryService.class),
                toolSessions, files);

        assertThat(conversations.delete(requester, "conversation-1")).isTrue();

        verify(files).deleteConversationFiles(requester, "conversation-1");
        verify(memory).clear("conversation-1");
        verify(toolSessions).evictSession("conversation-1");
    }
}

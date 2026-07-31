package org.oagi.score.gateway.http.api.ai_management.execution;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiApprovedExecution;
import org.oagi.score.gateway.http.api.ai_management.model.AiResolvedChange;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiApprovedChangeMessagesTest {

    @Test
    void reconstructsApprovedExecutionAsCorrelatedToolProtocolMessages() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.limitToolOutput("raw result", 42L, "apply_change"))
                .thenReturn("bounded result");
        List<Message> messages = new ArrayList<>();

        AiApprovedChangeMessages.append(messages,
                new AiApprovedExecution("apply_change", "{\"id\":7}", "raw result"),
                recorder, 42L);

        assertCorrelated(messages, "apply_change", "{\"id\":7}", "bounded result");
        verify(recorder).limitToolOutput("raw result", 42L, "apply_change");
    }

    @Test
    void reconstructsResolvedChangeThroughTheSameBoundary() {
        AiTrajectoryRecorder recorder = mock(AiTrajectoryRecorder.class);
        when(recorder.limitToolOutput("done", 10L, "rename"))
                .thenReturn("done");
        List<Message> messages = new ArrayList<>();

        AiApprovedChangeMessages.append(messages,
                new AiResolvedChange("rename", "{\"to\":\"B\"}", "done", true),
                recorder, 10L);

        assertCorrelated(messages, "rename", "{\"to\":\"B\"}", "done");
    }

    private void assertCorrelated(List<Message> messages, String toolName,
                                  String arguments, String result) {
        assertThat(messages).hasSize(2);
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        ToolResponseMessage response = (ToolResponseMessage) messages.get(1);
        AssistantMessage.ToolCall call = assistant.getToolCalls().getFirst();
        ToolResponseMessage.ToolResponse observation = response.getResponses().getFirst();
        assertThat(call.name()).isEqualTo(toolName);
        assertThat(call.arguments()).isEqualTo(arguments);
        assertThat(call.id()).startsWith("approved-");
        assertThat(observation.id()).isEqualTo(call.id());
        assertThat(observation.name()).isEqualTo(toolName);
        assertThat(observation.responseData()).isEqualTo(result);
    }
}

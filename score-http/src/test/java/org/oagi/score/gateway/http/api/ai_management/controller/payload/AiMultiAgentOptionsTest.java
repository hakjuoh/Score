package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiMultiAgentOptionsTest {

    @Test
    void defaultsMissingSettingsToBoundedSingleAgentExecution() {
        ChatRequest request = new ChatRequest("help", "request-1", null,
                "conversation-1", null, List.of(), null);

        assertThat(request.multiAgent()).isEqualTo(AiMultiAgentOptions.single());
        assertThat(request.multiAgent().asMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "enabled", false, "maxAgents", 3, "strategy", "balanced"));
    }

    @Test
    void normalizesAndPreservesTheSocketSnapshot() {
        AiMultiAgentOptions options = new AiMultiAgentOptions(true, 4, " Verification ");
        AiChatSocketRequest socket = new AiChatSocketRequest("request-1", "help", null,
                "conversation-1", null, List.of(), null, "model", "high", "openai",
                Map.of("verbosity", "low"), "ask", options);

        ChatRequest request = socket.toChatRequest();

        assertThat(request.multiAgent().active()).isTrue();
        assertThat(request.multiAgent().maxAgents()).isEqualTo(4);
        assertThat(request.multiAgent().strategy()).isEqualTo("verification");
        assertThat(request.withConversation("conversation-2", "model", "high", "openai", Map.of())
                .multiAgent()).isEqualTo(options);
    }

    @Test
    void rejectsEveryOutOfBoundsOrUnknownSettingEvenWhenDisabled() {
        assertThatThrownBy(() -> new AiMultiAgentOptions(false, 1, "balanced"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 2 and 4");
        assertThatThrownBy(() -> new AiMultiAgentOptions(true, 5, "balanced"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 2 and 4");
        assertThatThrownBy(() -> new AiMultiAgentOptions(false, 2, "unbounded"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balanced, creative, or verification");
    }
}

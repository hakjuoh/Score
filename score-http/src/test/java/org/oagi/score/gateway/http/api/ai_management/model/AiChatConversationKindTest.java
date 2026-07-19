package org.oagi.score.gateway.http.api.ai_management.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatConversationKindTest {

    @Test
    void defaultsMissingAndUnknownStoredValuesToRoot() {
        assertThat(AiChatConversationKind.fromStoredValue(null)).isEqualTo(AiChatConversationKind.ROOT);
        assertThat(AiChatConversationKind.fromStoredValue(" ")).isEqualTo(AiChatConversationKind.ROOT);
        assertThat(AiChatConversationKind.fromStoredValue("future_kind"))
                .isEqualTo(AiChatConversationKind.ROOT);
        assertThat(AiChatConversationKind.fromStoredValue("parallel"))
                .isEqualTo(AiChatConversationKind.PARALLEL);
    }
}

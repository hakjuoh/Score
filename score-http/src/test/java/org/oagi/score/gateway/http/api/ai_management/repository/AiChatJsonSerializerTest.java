package org.oagi.score.gateway.http.api.ai_management.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatJsonSerializerTest {

    @Test
    void returnsTheSharedSingletonInstance() {
        assertThat(AiChatJsonSerializer.getInstance())
                .isSameAs(AiChatJsonSerializer.getInstance());
    }
}

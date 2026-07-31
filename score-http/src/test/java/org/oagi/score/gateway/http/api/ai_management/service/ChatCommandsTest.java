package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChatCommandsTest {

    @Test
    void distinguishesCompactFromSimilarPrefixesAndBoundsInstructions() {
        assertThat(ChatCommands.parseCompact(
                ChatCommands.compactPrompt("focus on decisions")).instructions())
                .isEqualTo("focus on decisions");
        assertThat(ChatCommands.parseCompact(ChatCommands.COMPACT + "ion")).isNull();
        assertThatIllegalArgumentException().isThrownBy(() ->
                        ChatCommands.parseCompact(
                                ChatCommands.compactPrompt("x".repeat(2001))))
                .withMessage("Compact instructions must not exceed 2000 characters.");
    }

    @Test
    void acceptsCaseAndWhitespaceVariantsAtTheInstructionLimit() {
        assertThat(ChatCommands.parseCompact(null)).isNull();
        assertThat(ChatCommands.parseCompact("  ")).isNull();
        assertThat(ChatCommands.parseCompact("  /COMPACT\tkeep decisions  ").instructions())
                .isEqualTo("keep decisions");
        assertThat(ChatCommands.parseCompact(
                ChatCommands.compactPrompt("x".repeat(2000))).instructions())
                .hasSize(2000);
    }

    @Test
    void rendersTheCanonicalCommandAndNormalizesOptionalInstructions() {
        assertThat(ChatCommands.compactPrompt()).isEqualTo(ChatCommands.COMPACT);
        assertThat(ChatCommands.compactPrompt(null)).isEqualTo(ChatCommands.COMPACT);
        assertThat(ChatCommands.compactPrompt("  preserve IDs  "))
                .isEqualTo(ChatCommands.COMPACT + " preserve IDs");
    }
}

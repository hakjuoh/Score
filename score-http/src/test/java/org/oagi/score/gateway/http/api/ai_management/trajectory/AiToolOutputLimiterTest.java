package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiContextUsageInfo;
import org.oagi.score.gateway.http.api.ai_management.model.AiBoundedToolOutput;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolOutputLimiterTest {

    @Test
    void truncatesUnicodeOnlyAtAValidUtf8Boundary() {
        AiBoundedToolOutput bounded = AiToolOutputLimiter.bounded("🙂".repeat(100), 96L);

        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.value()).doesNotContain("�");
        assertThat(bounded.value().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(288);
        assertThat(bounded.returnedBytes())
                .isEqualTo(bounded.value().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void reservesOutputsAgainstTheRemainingConversationBudget() {
        AtomicLong floor = new AtomicLong(90L);
        AiToolOutputLimiter limiter = new AiToolOutputLimiter(
                new AiContextBudget("model", 120L, 10L, 90L, 10L, 100L, false),
                floor, false);

        AiBoundedToolOutput first = limiter.reserve("x".repeat(1000), 100L);
        AiBoundedToolOutput second = limiter.reserve("must not fit", 100L);

        assertThat(first.returnedBytes()).isLessThanOrEqualTo(30);
        assertThat(floor).hasValue(100L);
        assertThat(limiter.usageAfter(first)).isEqualTo(new AiContextUsageInfo(
                "model", 100L, 120L, 100L, 0L, 100.0,
                true, "tool_output_estimate"));
        assertThat(second.value()).isEmpty();
        assertThat(second.returnedBytes()).isZero();
    }

    @Test
    void distinguishesPerToolAndRemainingContextTruncation() {
        AiToolOutputLimiter configured = new AiToolOutputLimiter(
                new AiContextBudget("model", 1_000L, 10L, 900L, 10L, 100L, false),
                new AtomicLong(10L), false);
        AiToolOutputLimiter.Reservation perTool =
                configured.reserveWithCause("x".repeat(1000), 10L);
        AiToolOutputLimiter context = new AiToolOutputLimiter(
                new AiContextBudget("model", 120L, 10L, 90L, 10L, 100L, false),
                new AtomicLong(95L), false);
        AiToolOutputLimiter.Reservation remaining =
                context.reserveWithCause("x".repeat(1000), 100L);

        assertThat(perTool.truncationCause())
                .isEqualTo(AiToolOutputLimiter.TruncationCause.TOOL_OUTPUT_LIMIT);
        assertThat(perTool.effectiveTokenLimit()).isEqualTo(10L);
        assertThat(remaining.truncationCause())
                .isEqualTo(AiToolOutputLimiter.TruncationCause.REMAINING_CONTEXT);
        assertThat(remaining.effectiveTokenLimit()).isEqualTo(5L);
    }

    @Test
    void clampsAResetFloorBeforeReservingTheNextOutput() {
        AtomicLong floor = new AtomicLong(50L);
        AiToolOutputLimiter limiter = new AiToolOutputLimiter(
                new AiContextBudget("model", 120L, 10L, 90L, 10L, 100L, false),
                floor, false);

        limiter.resetEstimatedInputFloor(-10L);
        AiBoundedToolOutput bounded = limiter.reserve("abc", 100L);

        assertThat(bounded.value()).isEqualTo("abc");
        assertThat(floor).hasValue(1L);
        assertThat(limiter.usageAfter(bounded)).isEqualTo(new AiContextUsageInfo(
                "model", 1L, 120L, 100L, 99L, 1.0,
                true, "tool_output_estimate"));
    }

    @Test
    void saturatesTheEstimatedFloorInsteadOfOverflowing() {
        AtomicLong floor = new AtomicLong(Long.MAX_VALUE - 1L);
        AiToolOutputLimiter limiter = new AiToolOutputLimiter(null, floor, true);

        AiBoundedToolOutput bounded = limiter.reserve("abcdef", Long.MAX_VALUE);

        assertThat(bounded.value()).isEqualTo("abcdef");
        assertThat(floor).hasValue(Long.MAX_VALUE);
        assertThat(limiter.usageAfter(bounded)).isNull();
    }
}

package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRetrySafetyTest {

    private static final AgentRetrySafety.Activity IDLE =
            new AgentRetrySafety.Activity(0, 0, 0);

    @Test
    void blocksRetryAfterAnyCompletedOrExecutedToolActivity() {
        assertThat(AgentRetrySafety.changed(IDLE,
                new AgentRetrySafety.Activity(1, 0, 0))).isTrue();
        assertThat(AgentRetrySafety.changed(IDLE,
                new AgentRetrySafety.Activity(0, 1, 0))).isTrue();
    }

    @Test
    void blocksRetryWhileAnApprovalIsAlreadyPending() {
        AgentRetrySafety.Activity pendingBefore = new AgentRetrySafety.Activity(0, 0, 1);

        assertThat(AgentRetrySafety.changed(pendingBefore,
                new AgentRetrySafety.Activity(0, 0, 1))).isTrue();
        assertThat(AgentRetrySafety.changed(IDLE,
                new AgentRetrySafety.Activity(0, 0, 1))).isTrue();
    }

    @Test
    void permitsRetryWhenNoToolOrApprovalActivityChanged() {
        assertThat(AgentRetrySafety.changed(IDLE, IDLE)).isFalse();
    }
}

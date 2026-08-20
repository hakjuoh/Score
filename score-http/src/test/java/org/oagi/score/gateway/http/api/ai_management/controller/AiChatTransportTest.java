package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChangeReadBackException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatTransportTest {

    @Test
    void explainsPostChangeReadBackFailureAndRecoveryWithoutExposingInternals() {
        String message = AiChatTransport.safeMessage(new CompletionException(
                new AiChangeReadBackException(3)));

        assertThat(message)
                .contains("completed 3 change operations",
                        "could not verify the final state",
                        "changes that succeeded remain applied",
                        "Refresh or inspect the affected records",
                        "complete any remaining work")
                .doesNotContain("server log", "IllegalStateException", "read-back");
    }

    @Test
    void usesSingularGrammarForOneCompletedChange() {
        assertThat(AiChatTransport.safeMessage(new AiChangeReadBackException(1)))
                .contains("completed 1 change operation")
                .doesNotContain("1 change operations");
    }

    @Test
    void givesActionableSafeGuidanceForUnknownAndMissingFailures() {
        String internal = AiChatTransport.safeMessage(new IllegalStateException(
                "SQL syntax near app_user; Authorization=Bearer exposed-token"));
        String missing = AiChatTransport.safeMessage(null);

        assertThat(internal)
                .contains("Some steps may have completed",
                        "retry only the unfinished part",
                        "contact an administrator")
                .doesNotContain("server log", "SQL syntax", "app_user", "exposed-token");
        assertThat(missing).isEqualTo(internal);
    }

    @Test
    void explainsTimeoutEffectsAndRecovery() {
        assertThat(AiChatTransport.safeMessage(new TimeoutException("internal deadline")))
                .contains("operation timed out",
                        "changes already reported as completed remain applied",
                        "retry only the unfinished part")
                .doesNotContain("server log", "internal deadline");
        assertThat(AiChatTransport.terminalMessage("TIMED_OUT", null))
                .contains("No further assistant activity was received",
                        "changes already reported as completed remain applied",
                        "retry only the unfinished part")
                .doesNotContain("server log");
    }
}

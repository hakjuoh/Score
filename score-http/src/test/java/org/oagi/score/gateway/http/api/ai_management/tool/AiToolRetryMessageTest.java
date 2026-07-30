package org.oagi.score.gateway.http.api.ai_management.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolRetryMessageTest {

    @Test
    void formatsTheSafetyNetWhenArgumentsWereCorrected() {
        var notice = new AiToolRetryTracker.RetryNotice(
                "create_top_level_asbiep", true);

        assertThat(AiToolRetryMessage.format(notice))
                .isEqualTo("The previous create_top_level_asbiep call failed. "
                        + "I corrected the tool arguments and am retrying it.");
    }

    @Test
    void formatsTheSafetyNetForAnUnchangedRetry() {
        var notice = new AiToolRetryTracker.RetryNotice("get_release", false);

        assertThat(AiToolRetryMessage.format(notice))
                .isEqualTo("The previous get_release call failed. I am retrying it now.");
    }
}

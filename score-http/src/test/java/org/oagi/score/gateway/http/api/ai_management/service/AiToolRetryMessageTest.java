package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolRetryMessageTest {

    @Test
    void formatsTheSafetyNetInTheCurrentPromptLanguage() {
        var notice = new AiToolRetryTracker.RetryNotice("create_top_level_asbiep", true);

        assertThat(AiToolRetryMessage.format(notice,
                AiToolRetryMessage.languageOf("호출 인자를 고쳐 줘")))
                .isEqualTo("이전 create_top_level_asbiep 호출이 실패했습니다. "
                        + "툴 호출 인자를 수정해 다시 시도합니다.");
        assertThat(AiToolRetryMessage.format(notice,
                AiToolRetryMessage.languageOf("Fix the tool arguments")))
                .isEqualTo("The previous create_top_level_asbiep call failed. "
                        + "I corrected the tool arguments and am retrying it.");
    }

    @Test
    void explicitLanguageRequestsOverridePromptScriptDetection() {
        assertThat(AiToolRetryMessage.languageOf("영어로 답해줘. 호출을 다시 시도해."))
                .isEqualTo(AiToolRetryMessage.Language.ENGLISH);
        assertThat(AiToolRetryMessage.languageOf(
                "Please respond in Korean while retrying this call."))
                .isEqualTo(AiToolRetryMessage.Language.KOREAN);
    }
}

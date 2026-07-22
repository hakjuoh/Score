package org.oagi.score.gateway.http.api.ai_management.tool;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/** Formats the deterministic retry safety-net in the current prompt language. */
public final class AiToolRetryMessage {

    private static final Pattern ENGLISH_DIRECTIVE = Pattern.compile(
            "\\b(?:respond|reply|answer|write)\\s+in\\s+english\\b|영어로|영문으로",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern KOREAN_DIRECTIVE = Pattern.compile(
            "\\b(?:respond|reply|answer|write)\\s+in\\s+korean\\b|한국어로|한글로",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public enum Language { ENGLISH, KOREAN }

    private AiToolRetryMessage() {
    }

    public static Language languageOf(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return Language.ENGLISH;
        }
        String normalized = prompt.strip().toLowerCase(Locale.ROOT);
        if (ENGLISH_DIRECTIVE.matcher(normalized).find()) {
            return Language.ENGLISH;
        }
        if (KOREAN_DIRECTIVE.matcher(normalized).find()) {
            return Language.KOREAN;
        }
        if (normalized.codePoints()
                .anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3)) {
            return Language.KOREAN;
        }
        return Language.ENGLISH;
    }

    public static String format(AiToolRetryTracker.RetryNotice notice, Language language) {
        if (language == Language.KOREAN) {
            return notice.argumentsCorrected()
                    ? "이전 " + notice.toolName() + " 호출이 실패했습니다. 툴 호출 인자를 수정해 다시 시도합니다."
                    : "이전 " + notice.toolName() + " 호출이 실패했습니다. 지금 다시 시도합니다.";
        }
        return notice.argumentsCorrected()
                ? "The previous " + notice.toolName()
                        + " call failed. I corrected the tool arguments and am retrying it."
                : "The previous " + notice.toolName() + " call failed. I am retrying it now.";
    }
}

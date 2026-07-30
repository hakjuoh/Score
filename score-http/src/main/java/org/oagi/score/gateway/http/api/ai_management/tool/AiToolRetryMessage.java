package org.oagi.score.gateway.http.api.ai_management.tool;

/** Formats the deterministic retry safety-net. */
public final class AiToolRetryMessage {

    private AiToolRetryMessage() {
    }

    public static String format(AiToolRetryTracker.RetryNotice notice) {
        return notice.argumentsCorrected()
                ? "The previous " + notice.toolName()
                        + " call failed. I corrected the tool arguments and am retrying it."
                : "The previous " + notice.toolName() + " call failed. I am retrying it now.";
    }
}

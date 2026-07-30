package org.oagi.score.gateway.http.api.ai_management.model;

/**
 * Result of authorizing a guarded change tool invocation.
 *
 * @param allowed whether the tool invocation may proceed
 * @param notice approval notice when authorization is required
 */
public record AiChangeAuthorization(
        boolean allowed,
        AiChangeConfirmationNotice notice) {

    /**
     * Returns an authorization that permits the change immediately.
     */
    public static AiChangeAuthorization permitted() {
        return new AiChangeAuthorization(true, null);
    }

    /**
     * Returns an authorization that requires the supplied user approval notice.
     *
     * @param notice approval request presented to the user
     * @return blocked authorization carrying the notice
     */
    public static AiChangeAuthorization required(AiChangeConfirmationNotice notice) {
        return new AiChangeAuthorization(false, notice);
    }
}

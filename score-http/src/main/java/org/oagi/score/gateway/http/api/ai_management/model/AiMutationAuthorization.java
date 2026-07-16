package org.oagi.score.gateway.http.api.ai_management.model;

/**
 * Result of authorizing a guarded mutation tool invocation.
 *
 * @param allowed whether the tool invocation may proceed
 * @param notice approval notice when authorization is required
 */
public record AiMutationAuthorization(
        boolean allowed,
        AiMutationConfirmationNotice notice) {

    /**
     * Returns an authorization that permits the mutation immediately.
     */
    public static AiMutationAuthorization permitted() {
        return new AiMutationAuthorization(true, null);
    }

    /**
     * Returns an authorization that requires the supplied user approval notice.
     *
     * @param notice approval request presented to the user
     * @return blocked authorization carrying the notice
     */
    public static AiMutationAuthorization required(AiMutationConfirmationNotice notice) {
        return new AiMutationAuthorization(false, notice);
    }
}

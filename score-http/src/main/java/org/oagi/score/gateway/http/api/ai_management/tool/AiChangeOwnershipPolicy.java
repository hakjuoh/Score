package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.common.model.ScoreUser;

/**
 * Answers whether the requester owns the record a data-changing tool call would change.
 * The automatic permission mode skips approval for owner-scoped tools only when this
 * returns {@code true}, so an unverifiable target must be reported as not owned.
 */
public interface AiChangeOwnershipPolicy {

    /**
     * Reports every target as not owned. Used where no database is bound, so an owner-scoped
     * tool call falls back to explicit approval instead of running unapproved.
     */
    AiChangeOwnershipPolicy UNVERIFIED = (requester, toolName, arguments) -> false;

    /**
     * @param requester signed-in user the conversation belongs to
     * @param toolName exact data-changing tool name
     * @param arguments canonical JSON arguments the tool would be called with
     * @return {@code true} only when the changed record exists and the requester owns it
     */
    boolean requesterOwnsTarget(ScoreUser requester, String toolName, String arguments);

}

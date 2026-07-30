package org.oagi.score.gateway.http.api.ai_management.model;

/** Risk class of a data-changing assistant tool, used by the automatic permission mode. */
public enum AiChangeRisk {

    /** Creates brand-new data without changing anything that already exists. */
    UNRESTRICTED,

    /**
     * Changes data that already exists, so it is safe only while the requester owns the target.
     * Changing data owned by somebody else always requires explicit approval.
     */
    OWNER_SCOPED,

    /**
     * Deletes, discards, cancels, removes, transfers ownership, or moves a state forward.
     * These require explicit approval even when the requester owns the target.
     */
    ALWAYS_CONFIRM

}

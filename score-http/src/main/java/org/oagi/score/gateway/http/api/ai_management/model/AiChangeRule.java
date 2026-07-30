package org.oagi.score.gateway.http.api.ai_management.model;

/**
 * Automatic-mode rule for one data-changing tool.
 *
 * @param risk risk class that decides whether approval can be skipped
 * @param targetParameter tool argument carrying the identifier of the changed record,
 *                        set only for {@link AiChangeRisk#OWNER_SCOPED}
 * @param targetKind kind of record {@code targetParameter} identifies,
 *                   set only for {@link AiChangeRisk#OWNER_SCOPED}
 */
public record AiChangeRule(
        AiChangeRisk risk,
        String targetParameter,
        AiOwnedEntityKind targetKind) {

    /** Rule applied to every tool the catalog does not list, so unknown tools fail closed. */
    public static final AiChangeRule ALWAYS_CONFIRM =
            new AiChangeRule(AiChangeRisk.ALWAYS_CONFIRM, null, null);

    /** Rule for a tool that only creates new data. */
    public static final AiChangeRule UNRESTRICTED =
            new AiChangeRule(AiChangeRisk.UNRESTRICTED, null, null);

    /** Creates the rule for a tool that changes one existing record. */
    public static AiChangeRule ownerScoped(String targetParameter, AiOwnedEntityKind targetKind) {
        if (targetParameter == null || targetParameter.isBlank() || targetKind == null) {
            throw new IllegalArgumentException("An owner-scoped rule requires a target parameter and kind.");
        }
        return new AiChangeRule(AiChangeRisk.OWNER_SCOPED, targetParameter, targetKind);
    }

}

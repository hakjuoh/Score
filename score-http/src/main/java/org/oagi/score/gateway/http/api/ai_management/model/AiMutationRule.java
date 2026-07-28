package org.oagi.score.gateway.http.api.ai_management.model;

/**
 * Automatic-mode rule for one data-changing tool.
 *
 * @param risk risk class that decides whether approval can be skipped
 * @param targetParameter tool argument carrying the identifier of the changed record,
 *                        set only for {@link AiMutationRisk#OWNER_SCOPED}
 * @param targetKind kind of record {@code targetParameter} identifies,
 *                   set only for {@link AiMutationRisk#OWNER_SCOPED}
 */
public record AiMutationRule(
        AiMutationRisk risk,
        String targetParameter,
        AiOwnedEntityKind targetKind) {

    /** Rule applied to every tool the catalog does not list, so unknown tools fail closed. */
    public static final AiMutationRule ALWAYS_CONFIRM =
            new AiMutationRule(AiMutationRisk.ALWAYS_CONFIRM, null, null);

    /** Rule for a tool that only creates new data. */
    public static final AiMutationRule UNRESTRICTED =
            new AiMutationRule(AiMutationRisk.UNRESTRICTED, null, null);

    /** Creates the rule for a tool that changes one existing record. */
    public static AiMutationRule ownerScoped(String targetParameter, AiOwnedEntityKind targetKind) {
        if (targetParameter == null || targetParameter.isBlank() || targetKind == null) {
            throw new IllegalArgumentException("An owner-scoped rule requires a target parameter and kind.");
        }
        return new AiMutationRule(AiMutationRisk.OWNER_SCOPED, targetParameter, targetKind);
    }

}

package org.oagi.score.gateway.http.api.ai_management.tool;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationRisk;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationRule;
import org.oagi.score.gateway.http.api.ai_management.model.AiOwnedEntityKind;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiMutationRiskCatalogTest {

    /** Name shapes that remove data, or move state or ownership, and are never automatic. */
    private static final List<String> ALWAYS_CONFIRM_SHAPES = List.of(
            "delete_", "discard_", "cancel_", "remove_", "unassign_", "change_", "transfer_");

    @Test
    void classifiesAnythingUnlistedAsRequiringApproval() {
        assertThat(AiMutationRiskCatalog.ruleOf("future_mutation"))
                .isEqualTo(AiMutationRule.ALWAYS_CONFIRM);
        assertThat(AiMutationRiskCatalog.ruleOf(null)).isEqualTo(AiMutationRule.ALWAYS_CONFIRM);
        assertThat(AiMutationRiskCatalog.ruleOf(" ")).isEqualTo(AiMutationRule.ALWAYS_CONFIRM);
        assertThat(AiMutationRiskCatalog.ruleOf("Update_Business_Context"))
                .isEqualTo(AiMutationRule.ALWAYS_CONFIRM);
    }

    @Test
    void keepsRemovalsStateChangesAndOwnershipTransfersOutOfAutomaticMode() {
        assertThat(AiMutationRiskCatalog.rulesByToolName().entrySet())
                .filteredOn(entry -> ALWAYS_CONFIRM_SHAPES.stream()
                        .anyMatch(shape -> entry.getKey().startsWith(shape))
                        || entry.getKey().endsWith("_state"))
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.getValue().risk())
                        .as(entry.getKey())
                        .isEqualTo(AiMutationRisk.ALWAYS_CONFIRM));
    }

    @Test
    void resolvesEveryOwnerScopedToolToOneIdentifiedTarget() {
        Map<String, AiMutationRule> rules = AiMutationRiskCatalog.rulesByToolName();
        assertThat(rules.values())
                .filteredOn(rule -> rule.risk() == AiMutationRisk.OWNER_SCOPED)
                .isNotEmpty()
                .allSatisfy(rule -> {
                    assertThat(rule.targetParameter()).isNotBlank();
                    assertThat(rule.targetKind()).isNotNull();
                });
        assertThat(rules.values())
                .filteredOn(rule -> rule.risk() != AiMutationRisk.OWNER_SCOPED)
                .allSatisfy(rule -> {
                    assertThat(rule.targetParameter()).isNull();
                    assertThat(rule.targetKind()).isNull();
                });
    }

    @Test
    void separatesCreatingNewDataFromChangingDataThatAlreadyExists() {
        assertThat(AiMutationRiskCatalog.ruleOf("create_business_context").risk())
                .isEqualTo(AiMutationRisk.UNRESTRICTED);
        assertThat(AiMutationRiskCatalog.ruleOf("create_top_level_asbiep").risk())
                .isEqualTo(AiMutationRisk.UNRESTRICTED);
        assertThat(AiMutationRiskCatalog.ruleOf("update_business_context"))
                .isEqualTo(AiMutationRule.ownerScoped("biz_ctx_id", AiOwnedEntityKind.BIZ_CTX));
        assertThat(AiMutationRiskCatalog.ruleOf("update_bbie"))
                .isEqualTo(AiMutationRule.ownerScoped("bbie_id", AiOwnedEntityKind.BBIE));
        assertThat(AiMutationRiskCatalog.ruleOf("update_top_level_asbiep"))
                .isEqualTo(AiMutationRule.ownerScoped(
                        "top_level_asbiep_id", AiOwnedEntityKind.TOP_LEVEL_ASBIEP));
        // Adding a child changes the parent that already exists, so the parent's owner decides.
        assertThat(AiMutationRiskCatalog.ruleOf("create_code_list_value"))
                .isEqualTo(AiMutationRule.ownerScoped(
                        "code_list_manifest_id", AiOwnedEntityKind.CODE_LIST_MANIFEST));
        assertThat(AiMutationRiskCatalog.ruleOf("add_ascc_to_acc"))
                .isEqualTo(AiMutationRule.ownerScoped(
                        "acc_manifest_id", AiOwnedEntityKind.ACC_MANIFEST));
    }

    @Test
    void resolvesEveryOwnedEntityKindFromSomeTool() {
        assertThat(AiMutationRiskCatalog.rulesByToolName().values())
                .map(AiMutationRule::targetKind)
                .containsAll(List.of(AiOwnedEntityKind.values()));
    }

}

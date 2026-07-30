package org.oagi.score.gateway.http.api.ai_management.tool;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeRisk;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeRule;
import org.oagi.score.gateway.http.api.ai_management.model.AiOwnedEntityKind;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiChangeRiskCatalogTest {

    /** Name shapes that remove data, or move state or ownership, and are never automatic. */
    private static final List<String> ALWAYS_CONFIRM_SHAPES = List.of(
            "delete_", "discard_", "cancel_", "remove_", "unassign_", "change_", "transfer_");

    @Test
    void classifiesAnythingUnlistedAsRequiringApproval() {
        assertThat(AiChangeRiskCatalog.ruleOf("future_change"))
                .isEqualTo(AiChangeRule.ALWAYS_CONFIRM);
        assertThat(AiChangeRiskCatalog.ruleOf(null)).isEqualTo(AiChangeRule.ALWAYS_CONFIRM);
        assertThat(AiChangeRiskCatalog.ruleOf(" ")).isEqualTo(AiChangeRule.ALWAYS_CONFIRM);
        assertThat(AiChangeRiskCatalog.ruleOf("Update_Business_Context"))
                .isEqualTo(AiChangeRule.ALWAYS_CONFIRM);
    }

    @Test
    void keepsRemovalsStateChangesAndOwnershipTransfersOutOfAutomaticMode() {
        assertThat(AiChangeRiskCatalog.rulesByToolName().entrySet())
                .filteredOn(entry -> ALWAYS_CONFIRM_SHAPES.stream()
                        .anyMatch(shape -> entry.getKey().startsWith(shape))
                        || entry.getKey().endsWith("_state"))
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.getValue().risk())
                        .as(entry.getKey())
                        .isEqualTo(AiChangeRisk.ALWAYS_CONFIRM));
    }

    @Test
    void resolvesEveryOwnerScopedToolToOneIdentifiedTarget() {
        Map<String, AiChangeRule> rules = AiChangeRiskCatalog.rulesByToolName();
        assertThat(rules.values())
                .filteredOn(rule -> rule.risk() == AiChangeRisk.OWNER_SCOPED)
                .isNotEmpty()
                .allSatisfy(rule -> {
                    assertThat(rule.targetParameter()).isNotBlank();
                    assertThat(rule.targetKind()).isNotNull();
                });
        assertThat(rules.values())
                .filteredOn(rule -> rule.risk() != AiChangeRisk.OWNER_SCOPED)
                .allSatisfy(rule -> {
                    assertThat(rule.targetParameter()).isNull();
                    assertThat(rule.targetKind()).isNull();
                });
    }

    @Test
    void separatesCreatingNewDataFromChangingDataThatAlreadyExists() {
        assertThat(AiChangeRiskCatalog.ruleOf("create_business_context").risk())
                .isEqualTo(AiChangeRisk.UNRESTRICTED);
        assertThat(AiChangeRiskCatalog.ruleOf("create_top_level_asbiep").risk())
                .isEqualTo(AiChangeRisk.UNRESTRICTED);
        assertThat(AiChangeRiskCatalog.ruleOf("update_business_context"))
                .isEqualTo(AiChangeRule.ownerScoped("biz_ctx_id", AiOwnedEntityKind.BIZ_CTX));
        assertThat(AiChangeRiskCatalog.ruleOf("update_bbie"))
                .isEqualTo(AiChangeRule.ownerScoped("bbie_id", AiOwnedEntityKind.BBIE));
        assertThat(AiChangeRiskCatalog.ruleOf("update_top_level_asbiep"))
                .isEqualTo(AiChangeRule.ownerScoped(
                        "top_level_asbiep_id", AiOwnedEntityKind.TOP_LEVEL_ASBIEP));
        // Adding a child changes the parent that already exists, so the parent's owner decides.
        assertThat(AiChangeRiskCatalog.ruleOf("create_code_list_value"))
                .isEqualTo(AiChangeRule.ownerScoped(
                        "code_list_manifest_id", AiOwnedEntityKind.CODE_LIST_MANIFEST));
        assertThat(AiChangeRiskCatalog.ruleOf("add_ascc_to_acc"))
                .isEqualTo(AiChangeRule.ownerScoped(
                        "acc_manifest_id", AiOwnedEntityKind.ACC_MANIFEST));
    }

    @Test
    void resolvesEveryOwnedEntityKindFromSomeTool() {
        assertThat(AiChangeRiskCatalog.rulesByToolName().values())
                .map(AiChangeRule::targetKind)
                .containsAll(List.of(AiOwnedEntityKind.values()));
    }

}

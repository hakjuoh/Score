package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.model.AiChangeRule;
import org.oagi.score.gateway.http.api.ai_management.model.AiOwnedEntityKind;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Classifies every data-changing connectCenter MCP tool for the automatic permission mode.
 *
 * <p>Classification is by name, exactly and case-sensitively, because the risk of a tool is a
 * property of what that specific tool does. A tool the catalog does not list is treated as
 * {@link AiChangeRule#ALWAYS_CONFIRM}, so a new or renamed data-changing tool requires
 * approval until it is classified here.
 *
 * <p>The three classes are:
 * <ul>
 *   <li>unrestricted &mdash; creates new data and changes nothing that already exists;</li>
 *   <li>owner-scoped &mdash; changes one existing record, named by {@code targetParameter},
 *       so it is automatic only while the requester owns that record;</li>
 *   <li>always-confirm &mdash; deletes, discards, cancels, removes, unassigns, transfers
 *       ownership, or advances a state, which requires approval even for the owner.</li>
 * </ul>
 */
public final class AiChangeRiskCatalog {

    private static final Map<String, AiChangeRule> RULES = rules();

    private AiChangeRiskCatalog() {
    }

    /** Returns the rule for a tool, defaulting to always-confirm for anything unclassified. */
    public static AiChangeRule ruleOf(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return AiChangeRule.ALWAYS_CONFIRM;
        }
        return RULES.getOrDefault(toolName, AiChangeRule.ALWAYS_CONFIRM);
    }

    /** Returns the classified tool names, for tests and diagnostics. */
    public static Map<String, AiChangeRule> rulesByToolName() {
        return RULES;
    }

    private static Map<String, AiChangeRule> rules() {
        Map<String, AiChangeRule> rules = new LinkedHashMap<>();

        // Creates a new record and changes nothing that already exists.
        unrestricted(rules, "create_acc");
        unrestricted(rules, "create_asccp");
        unrestricted(rules, "create_bccp");
        unrestricted(rules, "create_dt");
        unrestricted(rules, "create_code_list");
        unrestricted(rules, "create_business_context");
        unrestricted(rules, "create_context_category");
        unrestricted(rules, "create_context_scheme");
        unrestricted(rules, "create_library");
        unrestricted(rules, "create_namespace");
        unrestricted(rules, "create_top_level_asbiep");

        // Adds a child to, or otherwise changes, one existing record.
        ownerScoped(rules, "create_asbie", "from_abie_id", AiOwnedEntityKind.ABIE);
        ownerScoped(rules, "create_bbie", "from_abie_id", AiOwnedEntityKind.ABIE);
        ownerScoped(rules, "create_bbie_sc", "bbie_id", AiOwnedEntityKind.BBIE);
        ownerScoped(rules, "create_business_context_value", "biz_ctx_id", AiOwnedEntityKind.BIZ_CTX);
        ownerScoped(rules, "create_code_list_value", "code_list_manifest_id",
                AiOwnedEntityKind.CODE_LIST_MANIFEST);
        ownerScoped(rules, "create_context_scheme_value", "ctx_scheme_id", AiOwnedEntityKind.CTX_SCHEME);
        ownerScoped(rules, "create_dt_sc", "dt_manifest_id", AiOwnedEntityKind.DT_MANIFEST);
        ownerScoped(rules, "add_ascc_to_acc", "acc_manifest_id", AiOwnedEntityKind.ACC_MANIFEST);
        ownerScoped(rules, "add_bcc_to_acc", "acc_manifest_id", AiOwnedEntityKind.ACC_MANIFEST);
        ownerScoped(rules, "add_tags_to_acc", "acc_manifest_id", AiOwnedEntityKind.ACC_MANIFEST);
        ownerScoped(rules, "add_tags_to_asccp", "asccp_manifest_id", AiOwnedEntityKind.ASCCP_MANIFEST);
        ownerScoped(rules, "add_tags_to_bccp", "bccp_manifest_id", AiOwnedEntityKind.BCCP_MANIFEST);
        ownerScoped(rules, "add_dt_tags", "dt_manifest_id", AiOwnedEntityKind.DT_MANIFEST);
        ownerScoped(rules, "add_library_release_dependency", "library_id", AiOwnedEntityKind.LIBRARY);
        ownerScoped(rules, "assign_biz_ctx_to_top_level_asbiep", "top_level_asbiep_id",
                AiOwnedEntityKind.TOP_LEVEL_ASBIEP);
        ownerScoped(rules, "reuse_top_level_asbiep", "asbie_id", AiOwnedEntityKind.ASBIE);
        ownerScoped(rules, "revise_or_amend_acc", "acc_manifest_id", AiOwnedEntityKind.ACC_MANIFEST);
        ownerScoped(rules, "revise_or_amend_asccp", "asccp_manifest_id", AiOwnedEntityKind.ASCCP_MANIFEST);
        ownerScoped(rules, "revise_or_amend_bccp", "bccp_manifest_id", AiOwnedEntityKind.BCCP_MANIFEST);
        ownerScoped(rules, "revise_or_amend_dt", "dt_manifest_id", AiOwnedEntityKind.DT_MANIFEST);
        ownerScoped(rules, "revise_or_amend_code_list", "code_list_manifest_id",
                AiOwnedEntityKind.CODE_LIST_MANIFEST);
        ownerScoped(rules, "update_acc", "acc_manifest_id", AiOwnedEntityKind.ACC_MANIFEST);
        ownerScoped(rules, "update_asccp", "asccp_manifest_id", AiOwnedEntityKind.ASCCP_MANIFEST);
        ownerScoped(rules, "update_bccp", "bccp_manifest_id", AiOwnedEntityKind.BCCP_MANIFEST);
        ownerScoped(rules, "update_ascc", "ascc_manifest_id", AiOwnedEntityKind.ASCC_MANIFEST);
        ownerScoped(rules, "update_bcc", "bcc_manifest_id", AiOwnedEntityKind.BCC_MANIFEST);
        ownerScoped(rules, "update_dt", "dt_manifest_id", AiOwnedEntityKind.DT_MANIFEST);
        ownerScoped(rules, "update_dt_sc", "dt_sc_manifest_id", AiOwnedEntityKind.DT_SC_MANIFEST);
        ownerScoped(rules, "update_code_list", "code_list_manifest_id",
                AiOwnedEntityKind.CODE_LIST_MANIFEST);
        ownerScoped(rules, "update_code_list_value", "code_list_value_manifest_id",
                AiOwnedEntityKind.CODE_LIST_VALUE_MANIFEST);
        ownerScoped(rules, "update_top_level_asbiep", "top_level_asbiep_id",
                AiOwnedEntityKind.TOP_LEVEL_ASBIEP);
        ownerScoped(rules, "update_asbie", "asbie_id", AiOwnedEntityKind.ASBIE);
        ownerScoped(rules, "update_bbie", "bbie_id", AiOwnedEntityKind.BBIE);
        ownerScoped(rules, "update_bbie_sc", "bbie_sc_id", AiOwnedEntityKind.BBIE_SC);
        ownerScoped(rules, "update_business_context", "biz_ctx_id", AiOwnedEntityKind.BIZ_CTX);
        ownerScoped(rules, "update_business_context_value", "biz_ctx_value_id",
                AiOwnedEntityKind.BIZ_CTX_VALUE);
        ownerScoped(rules, "update_context_category", "ctx_category_id", AiOwnedEntityKind.CTX_CATEGORY);
        ownerScoped(rules, "update_context_scheme", "ctx_scheme_id", AiOwnedEntityKind.CTX_SCHEME);
        ownerScoped(rules, "update_context_scheme_value", "ctx_scheme_value_id",
                AiOwnedEntityKind.CTX_SCHEME_VALUE);
        ownerScoped(rules, "update_library", "library_id", AiOwnedEntityKind.LIBRARY);
        ownerScoped(rules, "update_namespace", "namespace_id", AiOwnedEntityKind.NAMESPACE);

        // Removes data, or advances state or ownership, so the owner is asked as well.
        alwaysConfirm(rules, "delete_business_context");
        alwaysConfirm(rules, "delete_business_context_value");
        alwaysConfirm(rules, "delete_code_list_value");
        alwaysConfirm(rules, "delete_context_category");
        alwaysConfirm(rules, "delete_context_scheme");
        alwaysConfirm(rules, "delete_context_scheme_value");
        alwaysConfirm(rules, "delete_dt_sc");
        alwaysConfirm(rules, "delete_top_level_asbiep");
        alwaysConfirm(rules, "discard_acc");
        alwaysConfirm(rules, "discard_asccp");
        alwaysConfirm(rules, "discard_bccp");
        alwaysConfirm(rules, "discard_code_list");
        alwaysConfirm(rules, "discard_dt");
        alwaysConfirm(rules, "discard_library");
        alwaysConfirm(rules, "discard_namespace");
        alwaysConfirm(rules, "cancel_acc");
        alwaysConfirm(rules, "cancel_asccp");
        alwaysConfirm(rules, "cancel_bccp");
        alwaysConfirm(rules, "cancel_code_list");
        alwaysConfirm(rules, "cancel_dt");
        alwaysConfirm(rules, "change_acc_state");
        alwaysConfirm(rules, "change_asccp_state");
        alwaysConfirm(rules, "change_bccp_state");
        alwaysConfirm(rules, "change_code_list_state");
        alwaysConfirm(rules, "change_dt_state");
        alwaysConfirm(rules, "update_top_level_asbiep_state");
        alwaysConfirm(rules, "remove_ascc");
        alwaysConfirm(rules, "remove_bcc");
        alwaysConfirm(rules, "remove_dt_tags");
        alwaysConfirm(rules, "remove_library_release_dependency");
        alwaysConfirm(rules, "remove_reused_top_level_asbiep");
        alwaysConfirm(rules, "remove_tags_from_acc");
        alwaysConfirm(rules, "remove_tags_from_asccp");
        alwaysConfirm(rules, "remove_tags_from_bccp");
        alwaysConfirm(rules, "unassign_biz_ctx_from_top_level_asbiep");
        alwaysConfirm(rules, "transfer_acc_ownership");
        alwaysConfirm(rules, "transfer_asccp_ownership");
        alwaysConfirm(rules, "transfer_bccp_ownership");
        alwaysConfirm(rules, "transfer_code_list_ownership");
        alwaysConfirm(rules, "transfer_dt_ownership");
        alwaysConfirm(rules, "transfer_namespace_ownership");
        alwaysConfirm(rules, "transfer_top_level_asbiep_ownership");

        return Map.copyOf(rules);
    }

    private static void unrestricted(Map<String, AiChangeRule> rules, String toolName) {
        put(rules, toolName, AiChangeRule.UNRESTRICTED);
    }

    private static void alwaysConfirm(Map<String, AiChangeRule> rules, String toolName) {
        put(rules, toolName, AiChangeRule.ALWAYS_CONFIRM);
    }

    private static void ownerScoped(Map<String, AiChangeRule> rules, String toolName,
                                    String targetParameter, AiOwnedEntityKind targetKind) {
        put(rules, toolName, AiChangeRule.ownerScoped(targetParameter, targetKind));
    }

    private static void put(Map<String, AiChangeRule> rules, String toolName, AiChangeRule rule) {
        if (rules.put(toolName, rule) != null) {
            throw new IllegalStateException("Tool is classified twice: " + toolName);
        }
    }

}

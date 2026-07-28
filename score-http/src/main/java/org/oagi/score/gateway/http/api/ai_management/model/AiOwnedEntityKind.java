package org.oagi.score.gateway.http.api.ai_management.model;

/**
 * Kind of connectCenter record an owner-scoped assistant tool changes, identified by the
 * tool argument that carries its identifier. Each kind resolves to exactly one owning user.
 */
public enum AiOwnedEntityKind {

    TOP_LEVEL_ASBIEP,
    ABIE,
    ASBIE,
    BBIE,
    BBIE_SC,
    ACC_MANIFEST,
    ASCCP_MANIFEST,
    BCCP_MANIFEST,
    DT_MANIFEST,
    DT_SC_MANIFEST,
    CODE_LIST_MANIFEST,
    CODE_LIST_VALUE_MANIFEST,
    ASCC_MANIFEST,
    BCC_MANIFEST,
    BIZ_CTX,
    BIZ_CTX_VALUE,
    CTX_CATEGORY,
    CTX_SCHEME,
    CTX_SCHEME_VALUE,
    NAMESPACE,
    LIBRARY

}

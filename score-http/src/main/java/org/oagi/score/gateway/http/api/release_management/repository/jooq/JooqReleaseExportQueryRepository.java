package org.oagi.score.gateway.http.api.release_management.repository.jooq;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.api.release_management.repository.ReleaseExportQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.*;

public class JooqReleaseExportQueryRepository extends JooqBaseRepository implements ReleaseExportQueryRepository {

    public JooqReleaseExportQueryRepository(DSLContext dslContext, ScoreUser requester, RepositoryFactory repositoryFactory) {
        super(dslContext, requester, repositoryFactory);
    }

    public List<Map<String, Object>> getExportAccRows(ReleaseId releaseId) {
        var basedAcc = ACC.as("based_acc");
        var replacementAcc = ACC.as("replacement_acc");
        var prevAcc = ACC.as("prev_acc");
        var nextAcc = ACC.as("next_acc");
        var namespace = NAMESPACE.as("acc_namespace");
        var createdBy = APP_USER.as("acc_created_by");
        var owner = APP_USER.as("acc_owner");
        var lastUpdatedBy = APP_USER.as("acc_last_updated_by");

        return dslContext().select(
                        ACC.ACC_ID,
                        ACC.GUID,
                        ACC.TYPE,
                        ACC.OBJECT_CLASS_TERM,
                        ACC.DEFINITION,
                        ACC.DEFINITION_SOURCE,
                        ACC.OBJECT_CLASS_QUALIFIER,
                        ACC.OAGIS_COMPONENT_TYPE,
                        ACC.STATE,
                        ACC.IS_DEPRECATED,
                        ACC.IS_ABSTRACT,
                        namespace.URI.as("namespace_uri"),
                        basedAcc.GUID.as("based_acc_guid"),
                        replacementAcc.GUID.as("replacement_acc_guid"),
                        prevAcc.GUID.as("prev_acc_guid"),
                        nextAcc.GUID.as("next_acc_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        ACC.CREATION_TIMESTAMP,
                        ACC.LAST_UPDATE_TIMESTAMP)
                .from(ACC)
                .join(ACC_MANIFEST).on(ACC.ACC_ID.eq(ACC_MANIFEST.ACC_ID))
                .leftJoin(namespace).on(ACC.NAMESPACE_ID.eq(namespace.NAMESPACE_ID))
                .leftJoin(basedAcc).on(ACC.BASED_ACC_ID.eq(basedAcc.ACC_ID))
                .leftJoin(replacementAcc).on(ACC.REPLACEMENT_ACC_ID.eq(replacementAcc.ACC_ID))
                .leftJoin(prevAcc).on(ACC.PREV_ACC_ID.eq(prevAcc.ACC_ID))
                .leftJoin(nextAcc).on(ACC.NEXT_ACC_ID.eq(nextAcc.ACC_ID))
                .leftJoin(createdBy).on(ACC.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(ACC.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(ACC.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(ACC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(ACC.OBJECT_CLASS_TERM.asc(), ACC.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(ACC.ACC_ID)),
                        "guid", record.get(ACC.GUID),
                        "type", record.get(ACC.TYPE),
                        "object_class_term", record.get(ACC.OBJECT_CLASS_TERM),
                        "definition", record.get(ACC.DEFINITION),
                        "definition_source", record.get(ACC.DEFINITION_SOURCE),
                        "object_class_qualifier", record.get(ACC.OBJECT_CLASS_QUALIFIER),
                        "oagis_component_type", record.get(ACC.OAGIS_COMPONENT_TYPE),
                        "namespace_uri", record.get("namespace_uri", String.class),
                        "state", record.get(ACC.STATE),
                        "is_deprecated", record.get(ACC.IS_DEPRECATED),
                        "is_abstract", record.get(ACC.IS_ABSTRACT),
                        "based_acc_guid", record.get("based_acc_guid", String.class),
                        "replacement_acc_guid", record.get("replacement_acc_guid", String.class),
                        "prev_acc_guid", record.get("prev_acc_guid", String.class),
                        "next_acc_guid", record.get("next_acc_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(ACC.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(ACC.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportAccManifestRows(ReleaseId releaseId) {
        var basedManifest = ACC_MANIFEST.as("based_acc_manifest");
        var basedAcc = ACC.as("based_acc");
        var basedRelease = RELEASE.as("based_acc_release");
        var replacementManifest = ACC_MANIFEST.as("replacement_acc_manifest");
        var replacementAcc = ACC.as("replacement_acc");
        var replacementRelease = RELEASE.as("replacement_acc_release");
        var prevManifest = ACC_MANIFEST.as("prev_acc_manifest");
        var prevAcc = ACC.as("prev_acc");
        var prevRelease = RELEASE.as("prev_acc_release");
        var nextManifest = ACC_MANIFEST.as("next_acc_manifest");
        var nextAcc = ACC.as("next_acc");
        var nextRelease = RELEASE.as("next_acc_release");

        return dslContext().select(
                        ACC_MANIFEST.ACC_MANIFEST_ID,
                        ACC.GUID.as("acc_guid"),
                        basedAcc.GUID.as("based_acc_guid"),
                        basedRelease.GUID.as("based_acc_release_guid"),
                        ACC_MANIFEST.DEN,
                        ACC_MANIFEST.CONFLICT,
                        replacementAcc.GUID.as("replacement_acc_guid"),
                        replacementRelease.GUID.as("replacement_acc_release_guid"),
                        prevAcc.GUID.as("prev_acc_guid"),
                        prevRelease.GUID.as("prev_acc_release_guid"),
                        nextAcc.GUID.as("next_acc_guid"),
                        nextRelease.GUID.as("next_acc_release_guid"))
                .from(ACC_MANIFEST)
                .join(ACC).on(ACC_MANIFEST.ACC_ID.eq(ACC.ACC_ID))
                .leftJoin(basedManifest).on(ACC_MANIFEST.BASED_ACC_MANIFEST_ID.eq(basedManifest.ACC_MANIFEST_ID))
                .leftJoin(basedAcc).on(basedManifest.ACC_ID.eq(basedAcc.ACC_ID))
                .leftJoin(basedRelease).on(basedManifest.RELEASE_ID.eq(basedRelease.RELEASE_ID))
                .leftJoin(replacementManifest).on(ACC_MANIFEST.REPLACEMENT_ACC_MANIFEST_ID.eq(replacementManifest.ACC_MANIFEST_ID))
                .leftJoin(replacementAcc).on(replacementManifest.ACC_ID.eq(replacementAcc.ACC_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(ACC_MANIFEST.PREV_ACC_MANIFEST_ID.eq(prevManifest.ACC_MANIFEST_ID))
                .leftJoin(prevAcc).on(prevManifest.ACC_ID.eq(prevAcc.ACC_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(ACC_MANIFEST.NEXT_ACC_MANIFEST_ID.eq(nextManifest.ACC_MANIFEST_ID))
                .leftJoin(nextAcc).on(nextManifest.ACC_ID.eq(nextAcc.ACC_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(ACC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(ACC_MANIFEST.DEN.asc(), ACC.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(ACC_MANIFEST.ACC_MANIFEST_ID)),
                        "acc_guid", record.get("acc_guid", String.class),
                        "based_acc_guid", record.get("based_acc_guid", String.class),
                        "based_acc_release_guid", record.get("based_acc_release_guid", String.class),
                        "den", record.get(ACC_MANIFEST.DEN),
                        "conflict", record.get(ACC_MANIFEST.CONFLICT),
                        "replacement_acc_guid", record.get("replacement_acc_guid", String.class),
                        "replacement_acc_release_guid", record.get("replacement_acc_release_guid", String.class),
                        "prev_acc_guid", record.get("prev_acc_guid", String.class),
                        "prev_acc_release_guid", record.get("prev_acc_release_guid", String.class),
                        "next_acc_guid", record.get("next_acc_guid", String.class),
                        "next_acc_release_guid", record.get("next_acc_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportAsccRows(ReleaseId releaseId) {
        var fromAcc = ACC.as("from_acc");
        var toAsccp = ASCCP.as("to_asccp");
        var replacementAscc = ASCC.as("replacement_ascc");
        var prevAscc = ASCC.as("prev_ascc");
        var nextAscc = ASCC.as("next_ascc");
        var createdBy = APP_USER.as("ascc_created_by");
        var owner = APP_USER.as("ascc_owner");
        var lastUpdatedBy = APP_USER.as("ascc_last_updated_by");

        return dslContext().select(
                        ASCC.ASCC_ID,
                        ASCC.GUID,
                        ASCC.CARDINALITY_MIN,
                        ASCC.CARDINALITY_MAX,
                        ASCC.SEQ_KEY,
                        fromAcc.GUID.as("from_acc_guid"),
                        toAsccp.GUID.as("to_asccp_guid"),
                        ASCC.DEFINITION,
                        ASCC.DEFINITION_SOURCE,
                        ASCC.IS_DEPRECATED,
                        ASCC.STATE,
                        replacementAscc.GUID.as("replacement_ascc_guid"),
                        prevAscc.GUID.as("prev_ascc_guid"),
                        nextAscc.GUID.as("next_ascc_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        ASCC.CREATION_TIMESTAMP,
                        ASCC.LAST_UPDATE_TIMESTAMP)
                .from(ASCC)
                .join(ASCC_MANIFEST).on(ASCC.ASCC_ID.eq(ASCC_MANIFEST.ASCC_ID))
                .leftJoin(fromAcc).on(ASCC.FROM_ACC_ID.eq(fromAcc.ACC_ID))
                .leftJoin(toAsccp).on(ASCC.TO_ASCCP_ID.eq(toAsccp.ASCCP_ID))
                .leftJoin(replacementAscc).on(ASCC.REPLACEMENT_ASCC_ID.eq(replacementAscc.ASCC_ID))
                .leftJoin(prevAscc).on(ASCC.PREV_ASCC_ID.eq(prevAscc.ASCC_ID))
                .leftJoin(nextAscc).on(ASCC.NEXT_ASCC_ID.eq(nextAscc.ASCC_ID))
                .leftJoin(createdBy).on(ASCC.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(ASCC.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(ASCC.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(ASCC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(ASCC.SEQ_KEY.asc().nullsLast(), ASCC.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(ASCC.ASCC_ID)),
                        "guid", record.get(ASCC.GUID),
                        "cardinality_min", record.get(ASCC.CARDINALITY_MIN),
                        "cardinality_max", record.get(ASCC.CARDINALITY_MAX),
                        "seq_key", record.get(ASCC.SEQ_KEY),
                        "from_acc_guid", record.get("from_acc_guid", String.class),
                        "to_asccp_guid", record.get("to_asccp_guid", String.class),
                        "definition", record.get(ASCC.DEFINITION),
                        "definition_source", record.get(ASCC.DEFINITION_SOURCE),
                        "is_deprecated", record.get(ASCC.IS_DEPRECATED),
                        "state", record.get(ASCC.STATE),
                        "replacement_ascc_guid", record.get("replacement_ascc_guid", String.class),
                        "prev_ascc_guid", record.get("prev_ascc_guid", String.class),
                        "next_ascc_guid", record.get("next_ascc_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(ASCC.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(ASCC.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportAsccManifestRows(ReleaseId releaseId) {
        var fromManifest = ACC_MANIFEST.as("from_acc_manifest");
        var fromAcc = ACC.as("from_acc");
        var toManifest = ASCCP_MANIFEST.as("to_asccp_manifest");
        var toAsccp = ASCCP.as("to_asccp");
        var replacementManifest = ASCC_MANIFEST.as("replacement_ascc_manifest");
        var replacementAscc = ASCC.as("replacement_ascc");
        var replacementRelease = RELEASE.as("replacement_ascc_release");
        var prevManifest = ASCC_MANIFEST.as("prev_ascc_manifest");
        var prevAscc = ASCC.as("prev_ascc");
        var prevRelease = RELEASE.as("prev_ascc_release");
        var nextManifest = ASCC_MANIFEST.as("next_ascc_manifest");
        var nextAscc = ASCC.as("next_ascc");
        var nextRelease = RELEASE.as("next_ascc_release");

        return dslContext().select(
                        ASCC_MANIFEST.ASCC_MANIFEST_ID,
                        ASCC.GUID.as("ascc_guid"),
                        fromAcc.GUID.as("from_acc_guid"),
                        toAsccp.GUID.as("to_asccp_guid"),
                        ASCC_MANIFEST.DEN,
                        ASCC_MANIFEST.CONFLICT,
                        replacementAscc.GUID.as("replacement_ascc_guid"),
                        replacementRelease.GUID.as("replacement_ascc_release_guid"),
                        prevAscc.GUID.as("prev_ascc_guid"),
                        prevRelease.GUID.as("prev_ascc_release_guid"),
                        nextAscc.GUID.as("next_ascc_guid"),
                        nextRelease.GUID.as("next_ascc_release_guid"))
                .from(ASCC_MANIFEST)
                .join(ASCC).on(ASCC_MANIFEST.ASCC_ID.eq(ASCC.ASCC_ID))
                .leftJoin(fromManifest).on(ASCC_MANIFEST.FROM_ACC_MANIFEST_ID.eq(fromManifest.ACC_MANIFEST_ID))
                .leftJoin(fromAcc).on(fromManifest.ACC_ID.eq(fromAcc.ACC_ID))
                .leftJoin(toManifest).on(ASCC_MANIFEST.TO_ASCCP_MANIFEST_ID.eq(toManifest.ASCCP_MANIFEST_ID))
                .leftJoin(toAsccp).on(toManifest.ASCCP_ID.eq(toAsccp.ASCCP_ID))
                .leftJoin(replacementManifest).on(ASCC_MANIFEST.REPLACEMENT_ASCC_MANIFEST_ID.eq(replacementManifest.ASCC_MANIFEST_ID))
                .leftJoin(replacementAscc).on(replacementManifest.ASCC_ID.eq(replacementAscc.ASCC_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(ASCC_MANIFEST.PREV_ASCC_MANIFEST_ID.eq(prevManifest.ASCC_MANIFEST_ID))
                .leftJoin(prevAscc).on(prevManifest.ASCC_ID.eq(prevAscc.ASCC_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(ASCC_MANIFEST.NEXT_ASCC_MANIFEST_ID.eq(nextManifest.ASCC_MANIFEST_ID))
                .leftJoin(nextAscc).on(nextManifest.ASCC_ID.eq(nextAscc.ASCC_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(ASCC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(ASCC_MANIFEST.DEN.asc(), ASCC.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(ASCC_MANIFEST.ASCC_MANIFEST_ID)),
                        "ascc_guid", record.get("ascc_guid", String.class),
                        "from_acc_guid", record.get("from_acc_guid", String.class),
                        "to_asccp_guid", record.get("to_asccp_guid", String.class),
                        "den", record.get(ASCC_MANIFEST.DEN),
                        "conflict", record.get(ASCC_MANIFEST.CONFLICT),
                        "replacement_ascc_guid", record.get("replacement_ascc_guid", String.class),
                        "replacement_ascc_release_guid", record.get("replacement_ascc_release_guid", String.class),
                        "prev_ascc_guid", record.get("prev_ascc_guid", String.class),
                        "prev_ascc_release_guid", record.get("prev_ascc_release_guid", String.class),
                        "next_ascc_guid", record.get("next_ascc_guid", String.class),
                        "next_ascc_release_guid", record.get("next_ascc_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportBccRows(ReleaseId releaseId) {
        var fromAcc = ACC.as("from_acc");
        var toBccp = BCCP.as("to_bccp");
        var replacementBcc = BCC.as("replacement_bcc");
        var prevBcc = BCC.as("prev_bcc");
        var nextBcc = BCC.as("next_bcc");
        var createdBy = APP_USER.as("bcc_created_by");
        var owner = APP_USER.as("bcc_owner");
        var lastUpdatedBy = APP_USER.as("bcc_last_updated_by");

        return dslContext().select(
                        BCC.BCC_ID,
                        BCC.GUID,
                        BCC.CARDINALITY_MIN,
                        BCC.CARDINALITY_MAX,
                        BCC.SEQ_KEY,
                        BCC.ENTITY_TYPE,
                        fromAcc.GUID.as("from_acc_guid"),
                        toBccp.GUID.as("to_bccp_guid"),
                        BCC.DEFINITION,
                        BCC.DEFINITION_SOURCE,
                        BCC.IS_DEPRECATED,
                        BCC.IS_NILLABLE,
                        BCC.DEFAULT_VALUE,
                        BCC.FIXED_VALUE,
                        BCC.STATE,
                        replacementBcc.GUID.as("replacement_bcc_guid"),
                        prevBcc.GUID.as("prev_bcc_guid"),
                        nextBcc.GUID.as("next_bcc_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        BCC.CREATION_TIMESTAMP,
                        BCC.LAST_UPDATE_TIMESTAMP)
                .from(BCC)
                .join(BCC_MANIFEST).on(BCC.BCC_ID.eq(BCC_MANIFEST.BCC_ID))
                .leftJoin(fromAcc).on(BCC.FROM_ACC_ID.eq(fromAcc.ACC_ID))
                .leftJoin(toBccp).on(BCC.TO_BCCP_ID.eq(toBccp.BCCP_ID))
                .leftJoin(replacementBcc).on(BCC.REPLACEMENT_BCC_ID.eq(replacementBcc.BCC_ID))
                .leftJoin(prevBcc).on(BCC.PREV_BCC_ID.eq(prevBcc.BCC_ID))
                .leftJoin(nextBcc).on(BCC.NEXT_BCC_ID.eq(nextBcc.BCC_ID))
                .leftJoin(createdBy).on(BCC.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(BCC.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(BCC.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(BCC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(BCC.SEQ_KEY.asc().nullsLast(), BCC.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(BCC.BCC_ID)),
                        "guid", record.get(BCC.GUID),
                        "cardinality_min", record.get(BCC.CARDINALITY_MIN),
                        "cardinality_max", record.get(BCC.CARDINALITY_MAX),
                        "seq_key", record.get(BCC.SEQ_KEY),
                        "entity_type", record.get(BCC.ENTITY_TYPE),
                        "from_acc_guid", record.get("from_acc_guid", String.class),
                        "to_bccp_guid", record.get("to_bccp_guid", String.class),
                        "definition", record.get(BCC.DEFINITION),
                        "definition_source", record.get(BCC.DEFINITION_SOURCE),
                        "is_deprecated", record.get(BCC.IS_DEPRECATED),
                        "is_nillable", record.get(BCC.IS_NILLABLE),
                        "default_value", record.get(BCC.DEFAULT_VALUE),
                        "fixed_value", record.get(BCC.FIXED_VALUE),
                        "state", record.get(BCC.STATE),
                        "replacement_bcc_guid", record.get("replacement_bcc_guid", String.class),
                        "prev_bcc_guid", record.get("prev_bcc_guid", String.class),
                        "next_bcc_guid", record.get("next_bcc_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(BCC.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(BCC.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportBccManifestRows(ReleaseId releaseId) {
        var fromManifest = ACC_MANIFEST.as("from_acc_manifest");
        var fromAcc = ACC.as("from_acc");
        var toManifest = BCCP_MANIFEST.as("to_bccp_manifest");
        var toBccp = BCCP.as("to_bccp");
        var replacementManifest = BCC_MANIFEST.as("replacement_bcc_manifest");
        var replacementBcc = BCC.as("replacement_bcc");
        var replacementRelease = RELEASE.as("replacement_bcc_release");
        var prevManifest = BCC_MANIFEST.as("prev_bcc_manifest");
        var prevBcc = BCC.as("prev_bcc");
        var prevRelease = RELEASE.as("prev_bcc_release");
        var nextManifest = BCC_MANIFEST.as("next_bcc_manifest");
        var nextBcc = BCC.as("next_bcc");
        var nextRelease = RELEASE.as("next_bcc_release");

        return dslContext().select(
                        BCC_MANIFEST.BCC_MANIFEST_ID,
                        BCC.GUID.as("bcc_guid"),
                        fromAcc.GUID.as("from_acc_guid"),
                        toBccp.GUID.as("to_bccp_guid"),
                        BCC_MANIFEST.DEN,
                        BCC_MANIFEST.CONFLICT,
                        replacementBcc.GUID.as("replacement_bcc_guid"),
                        replacementRelease.GUID.as("replacement_bcc_release_guid"),
                        prevBcc.GUID.as("prev_bcc_guid"),
                        prevRelease.GUID.as("prev_bcc_release_guid"),
                        nextBcc.GUID.as("next_bcc_guid"),
                        nextRelease.GUID.as("next_bcc_release_guid"))
                .from(BCC_MANIFEST)
                .join(BCC).on(BCC_MANIFEST.BCC_ID.eq(BCC.BCC_ID))
                .leftJoin(fromManifest).on(BCC_MANIFEST.FROM_ACC_MANIFEST_ID.eq(fromManifest.ACC_MANIFEST_ID))
                .leftJoin(fromAcc).on(fromManifest.ACC_ID.eq(fromAcc.ACC_ID))
                .leftJoin(toManifest).on(BCC_MANIFEST.TO_BCCP_MANIFEST_ID.eq(toManifest.BCCP_MANIFEST_ID))
                .leftJoin(toBccp).on(toManifest.BCCP_ID.eq(toBccp.BCCP_ID))
                .leftJoin(replacementManifest).on(BCC_MANIFEST.REPLACEMENT_BCC_MANIFEST_ID.eq(replacementManifest.BCC_MANIFEST_ID))
                .leftJoin(replacementBcc).on(replacementManifest.BCC_ID.eq(replacementBcc.BCC_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(BCC_MANIFEST.PREV_BCC_MANIFEST_ID.eq(prevManifest.BCC_MANIFEST_ID))
                .leftJoin(prevBcc).on(prevManifest.BCC_ID.eq(prevBcc.BCC_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(BCC_MANIFEST.NEXT_BCC_MANIFEST_ID.eq(nextManifest.BCC_MANIFEST_ID))
                .leftJoin(nextBcc).on(nextManifest.BCC_ID.eq(nextBcc.BCC_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(BCC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(BCC_MANIFEST.DEN.asc(), BCC.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(BCC_MANIFEST.BCC_MANIFEST_ID)),
                        "bcc_guid", record.get("bcc_guid", String.class),
                        "from_acc_guid", record.get("from_acc_guid", String.class),
                        "to_bccp_guid", record.get("to_bccp_guid", String.class),
                        "den", record.get(BCC_MANIFEST.DEN),
                        "conflict", record.get(BCC_MANIFEST.CONFLICT),
                        "replacement_bcc_guid", record.get("replacement_bcc_guid", String.class),
                        "replacement_bcc_release_guid", record.get("replacement_bcc_release_guid", String.class),
                        "prev_bcc_guid", record.get("prev_bcc_guid", String.class),
                        "prev_bcc_release_guid", record.get("prev_bcc_release_guid", String.class),
                        "next_bcc_guid", record.get("next_bcc_guid", String.class),
                        "next_bcc_release_guid", record.get("next_bcc_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportAsccpRows(ReleaseId releaseId) {
        var roleOfAcc = ACC.as("role_of_acc");
        var namespace = NAMESPACE.as("asccp_namespace");
        var replacementAsccp = ASCCP.as("replacement_asccp");
        var prevAsccp = ASCCP.as("prev_asccp");
        var nextAsccp = ASCCP.as("next_asccp");
        var createdBy = APP_USER.as("asccp_created_by");
        var owner = APP_USER.as("asccp_owner");
        var lastUpdatedBy = APP_USER.as("asccp_last_updated_by");

        return dslContext().select(
                        ASCCP.ASCCP_ID,
                        ASCCP.GUID,
                        ASCCP.TYPE,
                        ASCCP.PROPERTY_TERM,
                        ASCCP.DEFINITION,
                        ASCCP.DEFINITION_SOURCE,
                        roleOfAcc.GUID.as("role_of_acc_guid"),
                        namespace.URI.as("namespace_uri"),
                        ASCCP.REUSABLE_INDICATOR,
                        ASCCP.IS_DEPRECATED,
                        ASCCP.IS_NILLABLE,
                        ASCCP.STATE,
                        replacementAsccp.GUID.as("replacement_asccp_guid"),
                        prevAsccp.GUID.as("prev_asccp_guid"),
                        nextAsccp.GUID.as("next_asccp_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        ASCCP.CREATION_TIMESTAMP,
                        ASCCP.LAST_UPDATE_TIMESTAMP)
                .from(ASCCP)
                .join(ASCCP_MANIFEST).on(ASCCP.ASCCP_ID.eq(ASCCP_MANIFEST.ASCCP_ID))
                .leftJoin(roleOfAcc).on(ASCCP.ROLE_OF_ACC_ID.eq(roleOfAcc.ACC_ID))
                .leftJoin(namespace).on(ASCCP.NAMESPACE_ID.eq(namespace.NAMESPACE_ID))
                .leftJoin(replacementAsccp).on(ASCCP.REPLACEMENT_ASCCP_ID.eq(replacementAsccp.ASCCP_ID))
                .leftJoin(prevAsccp).on(ASCCP.PREV_ASCCP_ID.eq(prevAsccp.ASCCP_ID))
                .leftJoin(nextAsccp).on(ASCCP.NEXT_ASCCP_ID.eq(nextAsccp.ASCCP_ID))
                .leftJoin(createdBy).on(ASCCP.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(ASCCP.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(ASCCP.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(ASCCP_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(ASCCP.PROPERTY_TERM.asc().nullsFirst(), ASCCP.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(ASCCP.ASCCP_ID)),
                        "guid", record.get(ASCCP.GUID),
                        "type", record.get(ASCCP.TYPE),
                        "property_term", record.get(ASCCP.PROPERTY_TERM),
                        "definition", record.get(ASCCP.DEFINITION),
                        "definition_source", record.get(ASCCP.DEFINITION_SOURCE),
                        "role_of_acc_guid", record.get("role_of_acc_guid", String.class),
                        "namespace_uri", record.get("namespace_uri", String.class),
                        "reusable_indicator", record.get(ASCCP.REUSABLE_INDICATOR),
                        "is_deprecated", record.get(ASCCP.IS_DEPRECATED),
                        "is_nillable", record.get(ASCCP.IS_NILLABLE),
                        "state", record.get(ASCCP.STATE),
                        "replacement_asccp_guid", record.get("replacement_asccp_guid", String.class),
                        "prev_asccp_guid", record.get("prev_asccp_guid", String.class),
                        "next_asccp_guid", record.get("next_asccp_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(ASCCP.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(ASCCP.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportAsccpManifestRows(ReleaseId releaseId) {
        var roleOfManifest = ACC_MANIFEST.as("role_of_acc_manifest");
        var roleOfAcc = ACC.as("role_of_acc");
        var replacementManifest = ASCCP_MANIFEST.as("replacement_asccp_manifest");
        var replacementAsccp = ASCCP.as("replacement_asccp");
        var replacementRelease = RELEASE.as("replacement_asccp_release");
        var prevManifest = ASCCP_MANIFEST.as("prev_asccp_manifest");
        var prevAsccp = ASCCP.as("prev_asccp");
        var prevRelease = RELEASE.as("prev_asccp_release");
        var nextManifest = ASCCP_MANIFEST.as("next_asccp_manifest");
        var nextAsccp = ASCCP.as("next_asccp");
        var nextRelease = RELEASE.as("next_asccp_release");

        return dslContext().select(
                        ASCCP_MANIFEST.ASCCP_MANIFEST_ID,
                        ASCCP.GUID.as("asccp_guid"),
                        roleOfAcc.GUID.as("role_of_acc_guid"),
                        ASCCP_MANIFEST.DEN,
                        ASCCP_MANIFEST.CONFLICT,
                        replacementAsccp.GUID.as("replacement_asccp_guid"),
                        replacementRelease.GUID.as("replacement_asccp_release_guid"),
                        prevAsccp.GUID.as("prev_asccp_guid"),
                        prevRelease.GUID.as("prev_asccp_release_guid"),
                        nextAsccp.GUID.as("next_asccp_guid"),
                        nextRelease.GUID.as("next_asccp_release_guid"))
                .from(ASCCP_MANIFEST)
                .join(ASCCP).on(ASCCP_MANIFEST.ASCCP_ID.eq(ASCCP.ASCCP_ID))
                .leftJoin(roleOfManifest).on(ASCCP_MANIFEST.ROLE_OF_ACC_MANIFEST_ID.eq(roleOfManifest.ACC_MANIFEST_ID))
                .leftJoin(roleOfAcc).on(roleOfManifest.ACC_ID.eq(roleOfAcc.ACC_ID))
                .leftJoin(replacementManifest).on(ASCCP_MANIFEST.REPLACEMENT_ASCCP_MANIFEST_ID.eq(replacementManifest.ASCCP_MANIFEST_ID))
                .leftJoin(replacementAsccp).on(replacementManifest.ASCCP_ID.eq(replacementAsccp.ASCCP_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(ASCCP_MANIFEST.PREV_ASCCP_MANIFEST_ID.eq(prevManifest.ASCCP_MANIFEST_ID))
                .leftJoin(prevAsccp).on(prevManifest.ASCCP_ID.eq(prevAsccp.ASCCP_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(ASCCP_MANIFEST.NEXT_ASCCP_MANIFEST_ID.eq(nextManifest.ASCCP_MANIFEST_ID))
                .leftJoin(nextAsccp).on(nextManifest.ASCCP_ID.eq(nextAsccp.ASCCP_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(ASCCP_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(ASCCP_MANIFEST.DEN.asc().nullsFirst(), ASCCP.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(ASCCP_MANIFEST.ASCCP_MANIFEST_ID)),
                        "asccp_guid", record.get("asccp_guid", String.class),
                        "role_of_acc_guid", record.get("role_of_acc_guid", String.class),
                        "den", record.get(ASCCP_MANIFEST.DEN),
                        "conflict", record.get(ASCCP_MANIFEST.CONFLICT),
                        "replacement_asccp_guid", record.get("replacement_asccp_guid", String.class),
                        "replacement_asccp_release_guid", record.get("replacement_asccp_release_guid", String.class),
                        "prev_asccp_guid", record.get("prev_asccp_guid", String.class),
                        "prev_asccp_release_guid", record.get("prev_asccp_release_guid", String.class),
                        "next_asccp_guid", record.get("next_asccp_guid", String.class),
                        "next_asccp_release_guid", record.get("next_asccp_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportBccpRows(ReleaseId releaseId) {
        var bdt = DT.as("bdt");
        var namespace = NAMESPACE.as("bccp_namespace");
        var replacementBccp = BCCP.as("replacement_bccp");
        var prevBccp = BCCP.as("prev_bccp");
        var nextBccp = BCCP.as("next_bccp");
        var createdBy = APP_USER.as("bccp_created_by");
        var owner = APP_USER.as("bccp_owner");
        var lastUpdatedBy = APP_USER.as("bccp_last_updated_by");

        return dslContext().select(
                        BCCP.BCCP_ID,
                        BCCP.GUID,
                        BCCP.PROPERTY_TERM,
                        BCCP.REPRESENTATION_TERM,
                        BCCP.DEFINITION,
                        BCCP.DEFINITION_SOURCE,
                        bdt.GUID.as("bdt_guid"),
                        namespace.URI.as("namespace_uri"),
                        BCCP.IS_DEPRECATED,
                        BCCP.IS_NILLABLE,
                        BCCP.DEFAULT_VALUE,
                        BCCP.FIXED_VALUE,
                        BCCP.STATE,
                        replacementBccp.GUID.as("replacement_bccp_guid"),
                        prevBccp.GUID.as("prev_bccp_guid"),
                        nextBccp.GUID.as("next_bccp_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        BCCP.CREATION_TIMESTAMP,
                        BCCP.LAST_UPDATE_TIMESTAMP)
                .from(BCCP)
                .join(BCCP_MANIFEST).on(BCCP.BCCP_ID.eq(BCCP_MANIFEST.BCCP_ID))
                .leftJoin(bdt).on(BCCP.BDT_ID.eq(bdt.DT_ID))
                .leftJoin(namespace).on(BCCP.NAMESPACE_ID.eq(namespace.NAMESPACE_ID))
                .leftJoin(replacementBccp).on(BCCP.REPLACEMENT_BCCP_ID.eq(replacementBccp.BCCP_ID))
                .leftJoin(prevBccp).on(BCCP.PREV_BCCP_ID.eq(prevBccp.BCCP_ID))
                .leftJoin(nextBccp).on(BCCP.NEXT_BCCP_ID.eq(nextBccp.BCCP_ID))
                .leftJoin(createdBy).on(BCCP.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(BCCP.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(BCCP.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(BCCP_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(BCCP.PROPERTY_TERM.asc(), BCCP.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(BCCP.BCCP_ID)),
                        "guid", record.get(BCCP.GUID),
                        "property_term", record.get(BCCP.PROPERTY_TERM),
                        "representation_term", record.get(BCCP.REPRESENTATION_TERM),
                        "definition", record.get(BCCP.DEFINITION),
                        "definition_source", record.get(BCCP.DEFINITION_SOURCE),
                        "bdt_guid", record.get("bdt_guid", String.class),
                        "namespace_uri", record.get("namespace_uri", String.class),
                        "is_deprecated", record.get(BCCP.IS_DEPRECATED),
                        "is_nillable", record.get(BCCP.IS_NILLABLE),
                        "default_value", record.get(BCCP.DEFAULT_VALUE),
                        "fixed_value", record.get(BCCP.FIXED_VALUE),
                        "state", record.get(BCCP.STATE),
                        "replacement_bccp_guid", record.get("replacement_bccp_guid", String.class),
                        "prev_bccp_guid", record.get("prev_bccp_guid", String.class),
                        "next_bccp_guid", record.get("next_bccp_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(BCCP.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(BCCP.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportBccpManifestRows(ReleaseId releaseId) {
        var bdtManifest = DT_MANIFEST.as("bdt_manifest");
        var bdt = DT.as("bdt");
        var replacementManifest = BCCP_MANIFEST.as("replacement_bccp_manifest");
        var replacementBccp = BCCP.as("replacement_bccp");
        var replacementRelease = RELEASE.as("replacement_bccp_release");
        var prevManifest = BCCP_MANIFEST.as("prev_bccp_manifest");
        var prevBccp = BCCP.as("prev_bccp");
        var prevRelease = RELEASE.as("prev_bccp_release");
        var nextManifest = BCCP_MANIFEST.as("next_bccp_manifest");
        var nextBccp = BCCP.as("next_bccp");
        var nextRelease = RELEASE.as("next_bccp_release");

        return dslContext().select(
                        BCCP_MANIFEST.BCCP_MANIFEST_ID,
                        BCCP.GUID.as("bccp_guid"),
                        bdt.GUID.as("bdt_guid"),
                        BCCP_MANIFEST.DEN,
                        BCCP_MANIFEST.CONFLICT,
                        replacementBccp.GUID.as("replacement_bccp_guid"),
                        replacementRelease.GUID.as("replacement_bccp_release_guid"),
                        prevBccp.GUID.as("prev_bccp_guid"),
                        prevRelease.GUID.as("prev_bccp_release_guid"),
                        nextBccp.GUID.as("next_bccp_guid"),
                        nextRelease.GUID.as("next_bccp_release_guid"))
                .from(BCCP_MANIFEST)
                .join(BCCP).on(BCCP_MANIFEST.BCCP_ID.eq(BCCP.BCCP_ID))
                .leftJoin(bdtManifest).on(BCCP_MANIFEST.BDT_MANIFEST_ID.eq(bdtManifest.DT_MANIFEST_ID))
                .leftJoin(bdt).on(bdtManifest.DT_ID.eq(bdt.DT_ID))
                .leftJoin(replacementManifest).on(BCCP_MANIFEST.REPLACEMENT_BCCP_MANIFEST_ID.eq(replacementManifest.BCCP_MANIFEST_ID))
                .leftJoin(replacementBccp).on(replacementManifest.BCCP_ID.eq(replacementBccp.BCCP_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(BCCP_MANIFEST.PREV_BCCP_MANIFEST_ID.eq(prevManifest.BCCP_MANIFEST_ID))
                .leftJoin(prevBccp).on(prevManifest.BCCP_ID.eq(prevBccp.BCCP_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(BCCP_MANIFEST.NEXT_BCCP_MANIFEST_ID.eq(nextManifest.BCCP_MANIFEST_ID))
                .leftJoin(nextBccp).on(nextManifest.BCCP_ID.eq(nextBccp.BCCP_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(BCCP_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(BCCP_MANIFEST.DEN.asc(), BCCP.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(BCCP_MANIFEST.BCCP_MANIFEST_ID)),
                        "bccp_guid", record.get("bccp_guid", String.class),
                        "bdt_guid", record.get("bdt_guid", String.class),
                        "den", record.get(BCCP_MANIFEST.DEN),
                        "conflict", record.get(BCCP_MANIFEST.CONFLICT),
                        "replacement_bccp_guid", record.get("replacement_bccp_guid", String.class),
                        "replacement_bccp_release_guid", record.get("replacement_bccp_release_guid", String.class),
                        "prev_bccp_guid", record.get("prev_bccp_guid", String.class),
                        "prev_bccp_release_guid", record.get("prev_bccp_release_guid", String.class),
                        "next_bccp_guid", record.get("next_bccp_guid", String.class),
                        "next_bccp_release_guid", record.get("next_bccp_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportDtRows(ReleaseId releaseId) {
        var basedDt = DT.as("based_dt");
        var replacementDt = DT.as("replacement_dt");
        var prevDt = DT.as("prev_dt");
        var nextDt = DT.as("next_dt");
        var namespace = NAMESPACE.as("dt_namespace");
        var createdBy = APP_USER.as("dt_created_by");
        var owner = APP_USER.as("dt_owner");
        var lastUpdatedBy = APP_USER.as("dt_last_updated_by");

        return dslContext().select(
                        DT.DT_ID,
                        DT.GUID,
                        DT.DATA_TYPE_TERM,
                        DT.QUALIFIER,
                        DT.REPRESENTATION_TERM,
                        DT.SIX_DIGIT_ID,
                        DT.DEFINITION,
                        DT.DEFINITION_SOURCE,
                        namespace.URI.as("namespace_uri"),
                        DT.CONTENT_COMPONENT_DEFINITION,
                        DT.STATE,
                        DT.COMMONLY_USED,
                        DT.IS_DEPRECATED,
                        basedDt.GUID.as("based_dt_guid"),
                        replacementDt.GUID.as("replacement_dt_guid"),
                        prevDt.GUID.as("prev_dt_guid"),
                        nextDt.GUID.as("next_dt_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        DT.CREATION_TIMESTAMP,
                        DT.LAST_UPDATE_TIMESTAMP)
                .from(DT)
                .join(DT_MANIFEST).on(DT.DT_ID.eq(DT_MANIFEST.DT_ID))
                .leftJoin(basedDt).on(DT.BASED_DT_ID.eq(basedDt.DT_ID))
                .leftJoin(replacementDt).on(DT.REPLACEMENT_DT_ID.eq(replacementDt.DT_ID))
                .leftJoin(prevDt).on(DT.PREV_DT_ID.eq(prevDt.DT_ID))
                .leftJoin(nextDt).on(DT.NEXT_DT_ID.eq(nextDt.DT_ID))
                .leftJoin(namespace).on(DT.NAMESPACE_ID.eq(namespace.NAMESPACE_ID))
                .leftJoin(createdBy).on(DT.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(DT.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(DT.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(DT_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(DT.DATA_TYPE_TERM.asc().nullsFirst(), DT.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(DT.DT_ID)),
                        "guid", record.get(DT.GUID),
                        "data_type_term", record.get(DT.DATA_TYPE_TERM),
                        "qualifier", record.get(DT.QUALIFIER),
                        "representation_term", record.get(DT.REPRESENTATION_TERM),
                        "six_digit_id", record.get(DT.SIX_DIGIT_ID),
                        "definition", record.get(DT.DEFINITION),
                        "definition_source", record.get(DT.DEFINITION_SOURCE),
                        "namespace_uri", record.get("namespace_uri", String.class),
                        "content_component_definition", record.get(DT.CONTENT_COMPONENT_DEFINITION),
                        "state", record.get(DT.STATE),
                        "commonly_used", record.get(DT.COMMONLY_USED),
                        "is_deprecated", record.get(DT.IS_DEPRECATED),
                        "based_dt_guid", record.get("based_dt_guid", String.class),
                        "replacement_dt_guid", record.get("replacement_dt_guid", String.class),
                        "prev_dt_guid", record.get("prev_dt_guid", String.class),
                        "next_dt_guid", record.get("next_dt_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(DT.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(DT.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportDtManifestRows(ReleaseId releaseId) {
        var basedManifest = DT_MANIFEST.as("based_dt_manifest");
        var basedDt = DT.as("based_dt");
        var basedRelease = RELEASE.as("based_dt_release");
        var replacementManifest = DT_MANIFEST.as("replacement_dt_manifest");
        var replacementDt = DT.as("replacement_dt");
        var replacementRelease = RELEASE.as("replacement_dt_release");
        var prevManifest = DT_MANIFEST.as("prev_dt_manifest");
        var prevDt = DT.as("prev_dt");
        var prevRelease = RELEASE.as("prev_dt_release");
        var nextManifest = DT_MANIFEST.as("next_dt_manifest");
        var nextDt = DT.as("next_dt");
        var nextRelease = RELEASE.as("next_dt_release");

        return dslContext().select(
                        DT_MANIFEST.DT_MANIFEST_ID,
                        DT.GUID.as("dt_guid"),
                        basedDt.GUID.as("based_dt_guid"),
                        basedRelease.GUID.as("based_dt_release_guid"),
                        DT_MANIFEST.DEN,
                        DT_MANIFEST.CONFLICT,
                        replacementDt.GUID.as("replacement_dt_guid"),
                        replacementRelease.GUID.as("replacement_dt_release_guid"),
                        prevDt.GUID.as("prev_dt_guid"),
                        prevRelease.GUID.as("prev_dt_release_guid"),
                        nextDt.GUID.as("next_dt_guid"),
                        nextRelease.GUID.as("next_dt_release_guid"))
                .from(DT_MANIFEST)
                .join(DT).on(DT_MANIFEST.DT_ID.eq(DT.DT_ID))
                .leftJoin(basedManifest).on(DT_MANIFEST.BASED_DT_MANIFEST_ID.eq(basedManifest.DT_MANIFEST_ID))
                .leftJoin(basedDt).on(basedManifest.DT_ID.eq(basedDt.DT_ID))
                .leftJoin(basedRelease).on(basedManifest.RELEASE_ID.eq(basedRelease.RELEASE_ID))
                .leftJoin(replacementManifest).on(DT_MANIFEST.REPLACEMENT_DT_MANIFEST_ID.eq(replacementManifest.DT_MANIFEST_ID))
                .leftJoin(replacementDt).on(replacementManifest.DT_ID.eq(replacementDt.DT_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(DT_MANIFEST.PREV_DT_MANIFEST_ID.eq(prevManifest.DT_MANIFEST_ID))
                .leftJoin(prevDt).on(prevManifest.DT_ID.eq(prevDt.DT_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(DT_MANIFEST.NEXT_DT_MANIFEST_ID.eq(nextManifest.DT_MANIFEST_ID))
                .leftJoin(nextDt).on(nextManifest.DT_ID.eq(nextDt.DT_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(DT_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(DT_MANIFEST.DEN.asc(), DT.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(DT_MANIFEST.DT_MANIFEST_ID)),
                        "dt_guid", record.get("dt_guid", String.class),
                        "based_dt_guid", record.get("based_dt_guid", String.class),
                        "based_dt_release_guid", record.get("based_dt_release_guid", String.class),
                        "den", record.get(DT_MANIFEST.DEN),
                        "conflict", record.get(DT_MANIFEST.CONFLICT),
                        "replacement_dt_guid", record.get("replacement_dt_guid", String.class),
                        "replacement_dt_release_guid", record.get("replacement_dt_release_guid", String.class),
                        "prev_dt_guid", record.get("prev_dt_guid", String.class),
                        "prev_dt_release_guid", record.get("prev_dt_release_guid", String.class),
                        "next_dt_guid", record.get("next_dt_guid", String.class),
                        "next_dt_release_guid", record.get("next_dt_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportDtScRows(ReleaseId releaseId) {
        var ownerDt = DT.as("owner_dt");
        var basedDtSc = DT_SC.as("based_dt_sc");
        var replacementDtSc = DT_SC.as("replacement_dt_sc");
        var prevDtSc = DT_SC.as("prev_dt_sc");
        var nextDtSc = DT_SC.as("next_dt_sc");
        var createdBy = APP_USER.as("dt_sc_created_by");
        var owner = APP_USER.as("dt_sc_owner");
        var lastUpdatedBy = APP_USER.as("dt_sc_last_updated_by");

        return dslContext().select(
                        DT_SC.DT_SC_ID,
                        DT_SC.GUID,
                        DT_SC.OBJECT_CLASS_TERM,
                        DT_SC.PROPERTY_TERM,
                        DT_SC.REPRESENTATION_TERM,
                        DT_SC.DEFINITION,
                        DT_SC.DEFINITION_SOURCE,
                        ownerDt.GUID.as("owner_dt_guid"),
                        DT_SC.CARDINALITY_MIN,
                        DT_SC.CARDINALITY_MAX,
                        basedDtSc.GUID.as("based_dt_sc_guid"),
                        DT_SC.DEFAULT_VALUE,
                        DT_SC.FIXED_VALUE,
                        DT_SC.IS_DEPRECATED,
                        replacementDtSc.GUID.as("replacement_dt_sc_guid"),
                        prevDtSc.GUID.as("prev_dt_sc_guid"),
                        nextDtSc.GUID.as("next_dt_sc_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        DT_SC.CREATION_TIMESTAMP,
                        DT_SC.LAST_UPDATE_TIMESTAMP)
                .from(DT_SC)
                .join(DT_SC_MANIFEST).on(DT_SC.DT_SC_ID.eq(DT_SC_MANIFEST.DT_SC_ID))
                .leftJoin(ownerDt).on(DT_SC.OWNER_DT_ID.eq(ownerDt.DT_ID))
                .leftJoin(basedDtSc).on(DT_SC.BASED_DT_SC_ID.eq(basedDtSc.DT_SC_ID))
                .leftJoin(replacementDtSc).on(DT_SC.REPLACEMENT_DT_SC_ID.eq(replacementDtSc.DT_SC_ID))
                .leftJoin(prevDtSc).on(DT_SC.PREV_DT_SC_ID.eq(prevDtSc.DT_SC_ID))
                .leftJoin(nextDtSc).on(DT_SC.NEXT_DT_SC_ID.eq(nextDtSc.DT_SC_ID))
                .leftJoin(createdBy).on(DT_SC.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(DT_SC.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(DT_SC.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(DT_SC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(DT_SC.PROPERTY_TERM.asc().nullsFirst(), DT_SC.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(DT_SC.DT_SC_ID)),
                        "guid", record.get(DT_SC.GUID),
                        "object_class_term", record.get(DT_SC.OBJECT_CLASS_TERM),
                        "property_term", record.get(DT_SC.PROPERTY_TERM),
                        "representation_term", record.get(DT_SC.REPRESENTATION_TERM),
                        "definition", record.get(DT_SC.DEFINITION),
                        "definition_source", record.get(DT_SC.DEFINITION_SOURCE),
                        "owner_dt_guid", record.get("owner_dt_guid", String.class),
                        "cardinality_min", record.get(DT_SC.CARDINALITY_MIN),
                        "cardinality_max", record.get(DT_SC.CARDINALITY_MAX),
                        "based_dt_sc_guid", record.get("based_dt_sc_guid", String.class),
                        "default_value", record.get(DT_SC.DEFAULT_VALUE),
                        "fixed_value", record.get(DT_SC.FIXED_VALUE),
                        "is_deprecated", record.get(DT_SC.IS_DEPRECATED),
                        "replacement_dt_sc_guid", record.get("replacement_dt_sc_guid", String.class),
                        "prev_dt_sc_guid", record.get("prev_dt_sc_guid", String.class),
                        "next_dt_sc_guid", record.get("next_dt_sc_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(DT_SC.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(DT_SC.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportDtScManifestRows(ReleaseId releaseId) {
        var ownerManifest = DT_MANIFEST.as("owner_dt_manifest");
        var ownerDt = DT.as("owner_dt");
        var basedManifest = DT_SC_MANIFEST.as("based_dt_sc_manifest");
        var basedDtSc = DT_SC.as("based_dt_sc");
        var basedRelease = RELEASE.as("based_dt_sc_release");
        var replacementManifest = DT_SC_MANIFEST.as("replacement_dt_sc_manifest");
        var replacementDtSc = DT_SC.as("replacement_dt_sc");
        var replacementRelease = RELEASE.as("replacement_dt_sc_release");
        var prevManifest = DT_SC_MANIFEST.as("prev_dt_sc_manifest");
        var prevDtSc = DT_SC.as("prev_dt_sc");
        var prevRelease = RELEASE.as("prev_dt_sc_release");
        var nextManifest = DT_SC_MANIFEST.as("next_dt_sc_manifest");
        var nextDtSc = DT_SC.as("next_dt_sc");
        var nextRelease = RELEASE.as("next_dt_sc_release");

        return dslContext().select(
                        DT_SC_MANIFEST.DT_SC_MANIFEST_ID,
                        DT_SC.GUID.as("dt_sc_guid"),
                        ownerDt.GUID.as("owner_dt_guid"),
                        basedDtSc.GUID.as("based_dt_sc_guid"),
                        basedRelease.GUID.as("based_dt_sc_release_guid"),
                        DT_SC_MANIFEST.CONFLICT,
                        replacementDtSc.GUID.as("replacement_dt_sc_guid"),
                        replacementRelease.GUID.as("replacement_dt_sc_release_guid"),
                        prevDtSc.GUID.as("prev_dt_sc_guid"),
                        prevRelease.GUID.as("prev_dt_sc_release_guid"),
                        nextDtSc.GUID.as("next_dt_sc_guid"),
                        nextRelease.GUID.as("next_dt_sc_release_guid"))
                .from(DT_SC_MANIFEST)
                .join(DT_SC).on(DT_SC_MANIFEST.DT_SC_ID.eq(DT_SC.DT_SC_ID))
                .leftJoin(ownerManifest).on(DT_SC_MANIFEST.OWNER_DT_MANIFEST_ID.eq(ownerManifest.DT_MANIFEST_ID))
                .leftJoin(ownerDt).on(ownerManifest.DT_ID.eq(ownerDt.DT_ID))
                .leftJoin(basedManifest).on(DT_SC_MANIFEST.BASED_DT_SC_MANIFEST_ID.eq(basedManifest.DT_SC_MANIFEST_ID))
                .leftJoin(basedDtSc).on(basedManifest.DT_SC_ID.eq(basedDtSc.DT_SC_ID))
                .leftJoin(basedRelease).on(basedManifest.RELEASE_ID.eq(basedRelease.RELEASE_ID))
                .leftJoin(replacementManifest).on(DT_SC_MANIFEST.REPLACEMENT_DT_SC_MANIFEST_ID.eq(replacementManifest.DT_SC_MANIFEST_ID))
                .leftJoin(replacementDtSc).on(replacementManifest.DT_SC_ID.eq(replacementDtSc.DT_SC_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(DT_SC_MANIFEST.PREV_DT_SC_MANIFEST_ID.eq(prevManifest.DT_SC_MANIFEST_ID))
                .leftJoin(prevDtSc).on(prevManifest.DT_SC_ID.eq(prevDtSc.DT_SC_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(DT_SC_MANIFEST.NEXT_DT_SC_MANIFEST_ID.eq(nextManifest.DT_SC_MANIFEST_ID))
                .leftJoin(nextDtSc).on(nextManifest.DT_SC_ID.eq(nextDtSc.DT_SC_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(DT_SC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(DT_SC.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(DT_SC_MANIFEST.DT_SC_MANIFEST_ID)),
                        "dt_sc_guid", record.get("dt_sc_guid", String.class),
                        "owner_dt_guid", record.get("owner_dt_guid", String.class),
                        "based_dt_sc_guid", record.get("based_dt_sc_guid", String.class),
                        "based_dt_sc_release_guid", record.get("based_dt_sc_release_guid", String.class),
                        "conflict", record.get(DT_SC_MANIFEST.CONFLICT),
                        "replacement_dt_sc_guid", record.get("replacement_dt_sc_guid", String.class),
                        "replacement_dt_sc_release_guid", record.get("replacement_dt_sc_release_guid", String.class),
                        "prev_dt_sc_guid", record.get("prev_dt_sc_guid", String.class),
                        "prev_dt_sc_release_guid", record.get("prev_dt_sc_release_guid", String.class),
                        "next_dt_sc_guid", record.get("next_dt_sc_guid", String.class),
                        "next_dt_sc_release_guid", record.get("next_dt_sc_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportDtAwdPriRows(ReleaseId releaseId) {
        var cdtPri = CDT_PRI.as("dt_awd_pri_cdt_pri");
        return dslContext().select(
                        DT_AWD_PRI.DT_AWD_PRI_ID,
                        DT.GUID.as("dt_guid"),
                        cdtPri.NAME.as("cdt_pri_name"),
                        XBT.GUID.as("xbt_guid"),
                        CODE_LIST.GUID.as("code_list_guid"),
                        AGENCY_ID_LIST.GUID.as("agency_id_list_guid"),
                        DT_AWD_PRI.IS_DEFAULT)
                .from(DT_AWD_PRI)
                .join(DT).on(DT_AWD_PRI.DT_ID.eq(DT.DT_ID))
                .leftJoin(cdtPri).on(DT_AWD_PRI.CDT_PRI_ID.eq(cdtPri.CDT_PRI_ID))
                .leftJoin(XBT_MANIFEST).on(DT_AWD_PRI.XBT_MANIFEST_ID.eq(XBT_MANIFEST.XBT_MANIFEST_ID))
                .leftJoin(XBT).on(XBT_MANIFEST.XBT_ID.eq(XBT.XBT_ID))
                .leftJoin(CODE_LIST_MANIFEST).on(DT_AWD_PRI.CODE_LIST_MANIFEST_ID.eq(CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID))
                .leftJoin(CODE_LIST).on(CODE_LIST_MANIFEST.CODE_LIST_ID.eq(CODE_LIST.CODE_LIST_ID))
                .leftJoin(AGENCY_ID_LIST_MANIFEST).on(DT_AWD_PRI.AGENCY_ID_LIST_MANIFEST_ID.eq(AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID))
                .leftJoin(AGENCY_ID_LIST).on(AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID.eq(AGENCY_ID_LIST.AGENCY_ID_LIST_ID))
                .where(DT_AWD_PRI.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(DT.GUID.asc(), DT_AWD_PRI.DT_AWD_PRI_ID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(DT_AWD_PRI.DT_AWD_PRI_ID)),
                        "dt_guid", record.get("dt_guid", String.class),
                        "cdt_pri_name", record.get("cdt_pri_name", String.class),
                        "xbt_guid", record.get("xbt_guid", String.class),
                        "code_list_guid", record.get("code_list_guid", String.class),
                        "agency_id_list_guid", record.get("agency_id_list_guid", String.class),
                        "is_default", record.get(DT_AWD_PRI.IS_DEFAULT)
                ));
    }

    public List<Map<String, Object>> getExportDtScAwdPriRows(ReleaseId releaseId) {
        var cdtPri = CDT_PRI.as("dt_sc_awd_pri_cdt_pri");
        return dslContext().select(
                        DT_SC_AWD_PRI.DT_SC_AWD_PRI_ID,
                        DT_SC.GUID.as("dt_sc_guid"),
                        cdtPri.NAME.as("cdt_pri_name"),
                        XBT.GUID.as("xbt_guid"),
                        CODE_LIST.GUID.as("code_list_guid"),
                        AGENCY_ID_LIST.GUID.as("agency_id_list_guid"),
                        DT_SC_AWD_PRI.IS_DEFAULT)
                .from(DT_SC_AWD_PRI)
                .join(DT_SC).on(DT_SC_AWD_PRI.DT_SC_ID.eq(DT_SC.DT_SC_ID))
                .leftJoin(cdtPri).on(DT_SC_AWD_PRI.CDT_PRI_ID.eq(cdtPri.CDT_PRI_ID))
                .leftJoin(XBT_MANIFEST).on(DT_SC_AWD_PRI.XBT_MANIFEST_ID.eq(XBT_MANIFEST.XBT_MANIFEST_ID))
                .leftJoin(XBT).on(XBT_MANIFEST.XBT_ID.eq(XBT.XBT_ID))
                .leftJoin(CODE_LIST_MANIFEST).on(DT_SC_AWD_PRI.CODE_LIST_MANIFEST_ID.eq(CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID))
                .leftJoin(CODE_LIST).on(CODE_LIST_MANIFEST.CODE_LIST_ID.eq(CODE_LIST.CODE_LIST_ID))
                .leftJoin(AGENCY_ID_LIST_MANIFEST).on(DT_SC_AWD_PRI.AGENCY_ID_LIST_MANIFEST_ID.eq(AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID))
                .leftJoin(AGENCY_ID_LIST).on(AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID.eq(AGENCY_ID_LIST.AGENCY_ID_LIST_ID))
                .where(DT_SC_AWD_PRI.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(DT_SC.GUID.asc(), DT_SC_AWD_PRI.DT_SC_AWD_PRI_ID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(DT_SC_AWD_PRI.DT_SC_AWD_PRI_ID)),
                        "dt_sc_guid", record.get("dt_sc_guid", String.class),
                        "cdt_pri_name", record.get("cdt_pri_name", String.class),
                        "xbt_guid", record.get("xbt_guid", String.class),
                        "code_list_guid", record.get("code_list_guid", String.class),
                        "agency_id_list_guid", record.get("agency_id_list_guid", String.class),
                        "is_default", record.get(DT_SC_AWD_PRI.IS_DEFAULT)
                ));
    }

    public List<Map<String, Object>> getExportCodeListRows(ReleaseId releaseId) {
        var namespace = NAMESPACE.as("code_list_namespace");
        var basedCodeList = CODE_LIST.as("based_code_list");
        var replacementCodeList = CODE_LIST.as("replacement_code_list");
        var prevCodeList = CODE_LIST.as("prev_code_list");
        var nextCodeList = CODE_LIST.as("next_code_list");
        var createdBy = APP_USER.as("code_list_created_by");
        var owner = APP_USER.as("code_list_owner");
        var lastUpdatedBy = APP_USER.as("code_list_last_updated_by");

        return dslContext().select(
                        CODE_LIST.CODE_LIST_ID,
                        CODE_LIST.GUID,
                        CODE_LIST.ENUM_TYPE_GUID,
                        CODE_LIST.NAME,
                        CODE_LIST.LIST_ID,
                        CODE_LIST.VERSION_ID,
                        CODE_LIST.DEFINITION,
                        CODE_LIST.REMARK,
                        CODE_LIST.DEFINITION_SOURCE,
                        namespace.URI.as("namespace_uri"),
                        CODE_LIST.EXTENSIBLE_INDICATOR,
                        CODE_LIST.STATE,
                        CODE_LIST.IS_DEPRECATED,
                        basedCodeList.GUID.as("based_code_list_guid"),
                        replacementCodeList.GUID.as("replacement_code_list_guid"),
                        prevCodeList.GUID.as("prev_code_list_guid"),
                        nextCodeList.GUID.as("next_code_list_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        CODE_LIST.CREATION_TIMESTAMP,
                        CODE_LIST.LAST_UPDATE_TIMESTAMP)
                .from(CODE_LIST)
                .join(CODE_LIST_MANIFEST).on(CODE_LIST.CODE_LIST_ID.eq(CODE_LIST_MANIFEST.CODE_LIST_ID))
                .leftJoin(namespace).on(CODE_LIST.NAMESPACE_ID.eq(namespace.NAMESPACE_ID))
                .leftJoin(basedCodeList).on(CODE_LIST.BASED_CODE_LIST_ID.eq(basedCodeList.CODE_LIST_ID))
                .leftJoin(replacementCodeList).on(CODE_LIST.REPLACEMENT_CODE_LIST_ID.eq(replacementCodeList.CODE_LIST_ID))
                .leftJoin(prevCodeList).on(CODE_LIST.PREV_CODE_LIST_ID.eq(prevCodeList.CODE_LIST_ID))
                .leftJoin(nextCodeList).on(CODE_LIST.NEXT_CODE_LIST_ID.eq(nextCodeList.CODE_LIST_ID))
                .leftJoin(createdBy).on(CODE_LIST.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(CODE_LIST.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(CODE_LIST.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(CODE_LIST_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(CODE_LIST.NAME.asc().nullsFirst(), CODE_LIST.LIST_ID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(CODE_LIST.CODE_LIST_ID)),
                        "guid", record.get(CODE_LIST.GUID),
                        "enum_type_guid", record.get(CODE_LIST.ENUM_TYPE_GUID),
                        "name", record.get(CODE_LIST.NAME),
                        "list_id", record.get(CODE_LIST.LIST_ID),
                        "version_id", record.get(CODE_LIST.VERSION_ID),
                        "definition", record.get(CODE_LIST.DEFINITION),
                        "remark", record.get(CODE_LIST.REMARK),
                        "definition_source", record.get(CODE_LIST.DEFINITION_SOURCE),
                        "namespace_uri", record.get("namespace_uri", String.class),
                        "extensible_indicator", record.get(CODE_LIST.EXTENSIBLE_INDICATOR),
                        "state", record.get(CODE_LIST.STATE),
                        "is_deprecated", record.get(CODE_LIST.IS_DEPRECATED),
                        "based_code_list_guid", record.get("based_code_list_guid", String.class),
                        "replacement_code_list_guid", record.get("replacement_code_list_guid", String.class),
                        "prev_code_list_guid", record.get("prev_code_list_guid", String.class),
                        "next_code_list_guid", record.get("next_code_list_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(CODE_LIST.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(CODE_LIST.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportCodeListManifestRows(ReleaseId releaseId) {
        var basedManifest = CODE_LIST_MANIFEST.as("based_code_list_manifest");
        var basedCodeList = CODE_LIST.as("based_code_list");
        var basedRelease = RELEASE.as("based_code_list_release");
        var agencyValueManifest = AGENCY_ID_LIST_VALUE_MANIFEST.as("agency_id_list_value_manifest");
        var agencyValue = AGENCY_ID_LIST_VALUE.as("agency_id_list_value");
        var replacementManifest = CODE_LIST_MANIFEST.as("replacement_code_list_manifest");
        var replacementCodeList = CODE_LIST.as("replacement_code_list");
        var replacementRelease = RELEASE.as("replacement_code_list_release");
        var prevManifest = CODE_LIST_MANIFEST.as("prev_code_list_manifest");
        var prevCodeList = CODE_LIST.as("prev_code_list");
        var prevRelease = RELEASE.as("prev_code_list_release");
        var nextManifest = CODE_LIST_MANIFEST.as("next_code_list_manifest");
        var nextCodeList = CODE_LIST.as("next_code_list");
        var nextRelease = RELEASE.as("next_code_list_release");

        return dslContext().select(
                        CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID,
                        CODE_LIST.GUID.as("code_list_guid"),
                        basedCodeList.GUID.as("based_code_list_guid"),
                        basedRelease.GUID.as("based_code_list_release_guid"),
                        agencyValue.GUID.as("agency_id_list_value_guid"),
                        agencyValue.VALUE.as("agency_id_list_value"),
                        CODE_LIST_MANIFEST.CONFLICT,
                        replacementCodeList.GUID.as("replacement_code_list_guid"),
                        replacementRelease.GUID.as("replacement_code_list_release_guid"),
                        prevCodeList.GUID.as("prev_code_list_guid"),
                        prevRelease.GUID.as("prev_code_list_release_guid"),
                        nextCodeList.GUID.as("next_code_list_guid"),
                        nextRelease.GUID.as("next_code_list_release_guid"))
                .from(CODE_LIST_MANIFEST)
                .join(CODE_LIST).on(CODE_LIST_MANIFEST.CODE_LIST_ID.eq(CODE_LIST.CODE_LIST_ID))
                .leftJoin(basedManifest).on(CODE_LIST_MANIFEST.BASED_CODE_LIST_MANIFEST_ID.eq(basedManifest.CODE_LIST_MANIFEST_ID))
                .leftJoin(basedCodeList).on(basedManifest.CODE_LIST_ID.eq(basedCodeList.CODE_LIST_ID))
                .leftJoin(basedRelease).on(basedManifest.RELEASE_ID.eq(basedRelease.RELEASE_ID))
                .leftJoin(agencyValueManifest).on(CODE_LIST_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID.eq(agencyValueManifest.AGENCY_ID_LIST_VALUE_MANIFEST_ID))
                .leftJoin(agencyValue).on(agencyValueManifest.AGENCY_ID_LIST_VALUE_ID.eq(agencyValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(replacementManifest).on(CODE_LIST_MANIFEST.REPLACEMENT_CODE_LIST_MANIFEST_ID.eq(replacementManifest.CODE_LIST_MANIFEST_ID))
                .leftJoin(replacementCodeList).on(replacementManifest.CODE_LIST_ID.eq(replacementCodeList.CODE_LIST_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(CODE_LIST_MANIFEST.PREV_CODE_LIST_MANIFEST_ID.eq(prevManifest.CODE_LIST_MANIFEST_ID))
                .leftJoin(prevCodeList).on(prevManifest.CODE_LIST_ID.eq(prevCodeList.CODE_LIST_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(CODE_LIST_MANIFEST.NEXT_CODE_LIST_MANIFEST_ID.eq(nextManifest.CODE_LIST_MANIFEST_ID))
                .leftJoin(nextCodeList).on(nextManifest.CODE_LIST_ID.eq(nextCodeList.CODE_LIST_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(CODE_LIST_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(CODE_LIST.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID)),
                        "code_list_guid", record.get("code_list_guid", String.class),
                        "based_code_list_guid", record.get("based_code_list_guid", String.class),
                        "based_code_list_release_guid", record.get("based_code_list_release_guid", String.class),
                        "agency_id_list_value_guid", record.get("agency_id_list_value_guid", String.class),
                        "agency_id_list_value", record.get("agency_id_list_value", String.class),
                        "conflict", record.get(CODE_LIST_MANIFEST.CONFLICT),
                        "replacement_code_list_guid", record.get("replacement_code_list_guid", String.class),
                        "replacement_code_list_release_guid", record.get("replacement_code_list_release_guid", String.class),
                        "prev_code_list_guid", record.get("prev_code_list_guid", String.class),
                        "prev_code_list_release_guid", record.get("prev_code_list_release_guid", String.class),
                        "next_code_list_guid", record.get("next_code_list_guid", String.class),
                        "next_code_list_release_guid", record.get("next_code_list_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportCodeListValueRows(ReleaseId releaseId) {
        var ownerCodeList = CODE_LIST.as("owner_code_list");
        var basedCodeListValue = CODE_LIST_VALUE.as("based_code_list_value");
        var replacementCodeListValue = CODE_LIST_VALUE.as("replacement_code_list_value");
        var prevCodeListValue = CODE_LIST_VALUE.as("prev_code_list_value");
        var nextCodeListValue = CODE_LIST_VALUE.as("next_code_list_value");
        var createdBy = APP_USER.as("code_list_value_created_by");
        var owner = APP_USER.as("code_list_value_owner");
        var lastUpdatedBy = APP_USER.as("code_list_value_last_updated_by");

        return dslContext().select(
                        CODE_LIST_VALUE.CODE_LIST_VALUE_ID,
                        CODE_LIST_VALUE.GUID,
                        ownerCodeList.GUID.as("code_list_guid"),
                        basedCodeListValue.GUID.as("based_code_list_value_guid"),
                        CODE_LIST_VALUE.VALUE,
                        CODE_LIST_VALUE.MEANING,
                        CODE_LIST_VALUE.DEFINITION,
                        CODE_LIST_VALUE.DEFINITION_SOURCE,
                        CODE_LIST_VALUE.IS_DEPRECATED,
                        replacementCodeListValue.GUID.as("replacement_code_list_value_guid"),
                        prevCodeListValue.GUID.as("prev_code_list_value_guid"),
                        nextCodeListValue.GUID.as("next_code_list_value_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        CODE_LIST_VALUE.CREATION_TIMESTAMP,
                        CODE_LIST_VALUE.LAST_UPDATE_TIMESTAMP)
                .from(CODE_LIST_VALUE_MANIFEST)
                .join(CODE_LIST_VALUE).on(CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID.eq(CODE_LIST_VALUE.CODE_LIST_VALUE_ID))
                .join(ownerCodeList).on(CODE_LIST_VALUE.CODE_LIST_ID.eq(ownerCodeList.CODE_LIST_ID))
                .leftJoin(basedCodeListValue).on(CODE_LIST_VALUE.BASED_CODE_LIST_VALUE_ID.eq(basedCodeListValue.CODE_LIST_VALUE_ID))
                .leftJoin(replacementCodeListValue).on(CODE_LIST_VALUE.REPLACEMENT_CODE_LIST_VALUE_ID.eq(replacementCodeListValue.CODE_LIST_VALUE_ID))
                .leftJoin(prevCodeListValue).on(CODE_LIST_VALUE.PREV_CODE_LIST_VALUE_ID.eq(prevCodeListValue.CODE_LIST_VALUE_ID))
                .leftJoin(nextCodeListValue).on(CODE_LIST_VALUE.NEXT_CODE_LIST_VALUE_ID.eq(nextCodeListValue.CODE_LIST_VALUE_ID))
                .leftJoin(createdBy).on(CODE_LIST_VALUE.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(CODE_LIST_VALUE.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(CODE_LIST_VALUE.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(CODE_LIST_VALUE_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(CODE_LIST_VALUE.VALUE.asc(), CODE_LIST_VALUE.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(CODE_LIST_VALUE.CODE_LIST_VALUE_ID)),
                        "guid", record.get(CODE_LIST_VALUE.GUID),
                        "code_list_guid", record.get("code_list_guid", String.class),
                        "based_code_list_value_guid", record.get("based_code_list_value_guid", String.class),
                        "value", record.get(CODE_LIST_VALUE.VALUE),
                        "meaning", record.get(CODE_LIST_VALUE.MEANING),
                        "definition", record.get(CODE_LIST_VALUE.DEFINITION),
                        "definition_source", record.get(CODE_LIST_VALUE.DEFINITION_SOURCE),
                        "is_deprecated", record.get(CODE_LIST_VALUE.IS_DEPRECATED),
                        "replacement_code_list_value_guid", record.get("replacement_code_list_value_guid", String.class),
                        "prev_code_list_value_guid", record.get("prev_code_list_value_guid", String.class),
                        "next_code_list_value_guid", record.get("next_code_list_value_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(CODE_LIST_VALUE.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(CODE_LIST_VALUE.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportCodeListValueManifestRows(ReleaseId releaseId) {
        var codeListManifest = CODE_LIST_MANIFEST.as("code_list_manifest");
        var ownerCodeList = CODE_LIST.as("owner_code_list");
        var basedManifest = CODE_LIST_VALUE_MANIFEST.as("based_code_list_value_manifest");
        var basedCodeListValue = CODE_LIST_VALUE.as("based_code_list_value");
        var basedRelease = RELEASE.as("based_code_list_value_release");
        var replacementManifest = CODE_LIST_VALUE_MANIFEST.as("replacement_code_list_value_manifest");
        var replacementCodeListValue = CODE_LIST_VALUE.as("replacement_code_list_value");
        var replacementRelease = RELEASE.as("replacement_code_list_value_release");
        var prevManifest = CODE_LIST_VALUE_MANIFEST.as("prev_code_list_value_manifest");
        var prevCodeListValue = CODE_LIST_VALUE.as("prev_code_list_value");
        var prevRelease = RELEASE.as("prev_code_list_value_release");
        var nextManifest = CODE_LIST_VALUE_MANIFEST.as("next_code_list_value_manifest");
        var nextCodeListValue = CODE_LIST_VALUE.as("next_code_list_value");
        var nextRelease = RELEASE.as("next_code_list_value_release");

        return dslContext().select(
                        CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID,
                        CODE_LIST_VALUE.GUID.as("code_list_value_guid"),
                        ownerCodeList.GUID.as("code_list_guid"),
                        basedCodeListValue.GUID.as("based_code_list_value_guid"),
                        basedRelease.GUID.as("based_code_list_value_release_guid"),
                        CODE_LIST_VALUE_MANIFEST.CONFLICT,
                        replacementCodeListValue.GUID.as("replacement_code_list_value_guid"),
                        replacementRelease.GUID.as("replacement_code_list_value_release_guid"),
                        prevCodeListValue.GUID.as("prev_code_list_value_guid"),
                        prevRelease.GUID.as("prev_code_list_value_release_guid"),
                        nextCodeListValue.GUID.as("next_code_list_value_guid"),
                        nextRelease.GUID.as("next_code_list_value_release_guid"))
                .from(CODE_LIST_VALUE_MANIFEST)
                .join(CODE_LIST_VALUE).on(CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID.eq(CODE_LIST_VALUE.CODE_LIST_VALUE_ID))
                .join(codeListManifest).on(CODE_LIST_VALUE_MANIFEST.CODE_LIST_MANIFEST_ID.eq(codeListManifest.CODE_LIST_MANIFEST_ID))
                .join(ownerCodeList).on(codeListManifest.CODE_LIST_ID.eq(ownerCodeList.CODE_LIST_ID))
                .leftJoin(basedManifest).on(CODE_LIST_VALUE_MANIFEST.BASED_CODE_LIST_VALUE_MANIFEST_ID.eq(basedManifest.CODE_LIST_VALUE_MANIFEST_ID))
                .leftJoin(basedCodeListValue).on(basedManifest.CODE_LIST_VALUE_ID.eq(basedCodeListValue.CODE_LIST_VALUE_ID))
                .leftJoin(basedRelease).on(basedManifest.RELEASE_ID.eq(basedRelease.RELEASE_ID))
                .leftJoin(replacementManifest).on(CODE_LIST_VALUE_MANIFEST.REPLACEMENT_CODE_LIST_VALUE_MANIFEST_ID.eq(replacementManifest.CODE_LIST_VALUE_MANIFEST_ID))
                .leftJoin(replacementCodeListValue).on(replacementManifest.CODE_LIST_VALUE_ID.eq(replacementCodeListValue.CODE_LIST_VALUE_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(CODE_LIST_VALUE_MANIFEST.PREV_CODE_LIST_VALUE_MANIFEST_ID.eq(prevManifest.CODE_LIST_VALUE_MANIFEST_ID))
                .leftJoin(prevCodeListValue).on(prevManifest.CODE_LIST_VALUE_ID.eq(prevCodeListValue.CODE_LIST_VALUE_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(CODE_LIST_VALUE_MANIFEST.NEXT_CODE_LIST_VALUE_MANIFEST_ID.eq(nextManifest.CODE_LIST_VALUE_MANIFEST_ID))
                .leftJoin(nextCodeListValue).on(nextManifest.CODE_LIST_VALUE_ID.eq(nextCodeListValue.CODE_LIST_VALUE_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(CODE_LIST_VALUE_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(
                        CODE_LIST_VALUE.GUID.asc(),
                        ownerCodeList.GUID.asc(),
                        CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID)),
                        "code_list_value_guid", record.get("code_list_value_guid", String.class),
                        "code_list_guid", record.get("code_list_guid", String.class),
                        "based_code_list_value_guid", record.get("based_code_list_value_guid", String.class),
                        "based_code_list_value_release_guid", record.get("based_code_list_value_release_guid", String.class),
                        "conflict", record.get(CODE_LIST_VALUE_MANIFEST.CONFLICT),
                        "replacement_code_list_value_guid", record.get("replacement_code_list_value_guid", String.class),
                        "replacement_code_list_value_release_guid", record.get("replacement_code_list_value_release_guid", String.class),
                        "prev_code_list_value_guid", record.get("prev_code_list_value_guid", String.class),
                        "prev_code_list_value_release_guid", record.get("prev_code_list_value_release_guid", String.class),
                        "next_code_list_value_guid", record.get("next_code_list_value_guid", String.class),
                        "next_code_list_value_release_guid", record.get("next_code_list_value_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportAgencyIdListRows(ReleaseId releaseId) {
        var namespace = NAMESPACE.as("agency_id_list_namespace");
        var agencyValue = AGENCY_ID_LIST_VALUE.as("agency_id_list_value");
        var basedAgencyList = AGENCY_ID_LIST.as("based_agency_id_list");
        var replacementAgencyList = AGENCY_ID_LIST.as("replacement_agency_id_list");
        var prevAgencyList = AGENCY_ID_LIST.as("prev_agency_id_list");
        var nextAgencyList = AGENCY_ID_LIST.as("next_agency_id_list");
        var createdBy = APP_USER.as("agency_id_list_created_by");
        var owner = APP_USER.as("agency_id_list_owner");
        var lastUpdatedBy = APP_USER.as("agency_id_list_last_updated_by");

        return dslContext().select(
                        AGENCY_ID_LIST.AGENCY_ID_LIST_ID,
                        AGENCY_ID_LIST.GUID,
                        AGENCY_ID_LIST.ENUM_TYPE_GUID,
                        AGENCY_ID_LIST.NAME,
                        AGENCY_ID_LIST.LIST_ID,
                        agencyValue.GUID.as("agency_id_list_value_guid"),
                        agencyValue.VALUE.as("agency_id_list_value"),
                        AGENCY_ID_LIST.VERSION_ID,
                        basedAgencyList.GUID.as("based_agency_id_list_guid"),
                        AGENCY_ID_LIST.DEFINITION,
                        AGENCY_ID_LIST.DEFINITION_SOURCE,
                        AGENCY_ID_LIST.REMARK,
                        namespace.URI.as("namespace_uri"),
                        AGENCY_ID_LIST.STATE,
                        AGENCY_ID_LIST.IS_DEPRECATED,
                        replacementAgencyList.GUID.as("replacement_agency_id_list_guid"),
                        prevAgencyList.GUID.as("prev_agency_id_list_guid"),
                        nextAgencyList.GUID.as("next_agency_id_list_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        AGENCY_ID_LIST.CREATION_TIMESTAMP,
                        AGENCY_ID_LIST.LAST_UPDATE_TIMESTAMP)
                .from(AGENCY_ID_LIST)
                .join(AGENCY_ID_LIST_MANIFEST).on(AGENCY_ID_LIST.AGENCY_ID_LIST_ID.eq(AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID))
                .leftJoin(agencyValue).on(AGENCY_ID_LIST.AGENCY_ID_LIST_VALUE_ID.eq(agencyValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(basedAgencyList).on(AGENCY_ID_LIST.BASED_AGENCY_ID_LIST_ID.eq(basedAgencyList.AGENCY_ID_LIST_ID))
                .leftJoin(namespace).on(AGENCY_ID_LIST.NAMESPACE_ID.eq(namespace.NAMESPACE_ID))
                .leftJoin(replacementAgencyList).on(AGENCY_ID_LIST.REPLACEMENT_AGENCY_ID_LIST_ID.eq(replacementAgencyList.AGENCY_ID_LIST_ID))
                .leftJoin(prevAgencyList).on(AGENCY_ID_LIST.PREV_AGENCY_ID_LIST_ID.eq(prevAgencyList.AGENCY_ID_LIST_ID))
                .leftJoin(nextAgencyList).on(AGENCY_ID_LIST.NEXT_AGENCY_ID_LIST_ID.eq(nextAgencyList.AGENCY_ID_LIST_ID))
                .leftJoin(createdBy).on(AGENCY_ID_LIST.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(AGENCY_ID_LIST.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(AGENCY_ID_LIST.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(AGENCY_ID_LIST_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(AGENCY_ID_LIST.NAME.asc().nullsFirst(), AGENCY_ID_LIST.LIST_ID.asc().nullsFirst())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(AGENCY_ID_LIST.AGENCY_ID_LIST_ID)),
                        "guid", record.get(AGENCY_ID_LIST.GUID),
                        "enum_type_guid", record.get(AGENCY_ID_LIST.ENUM_TYPE_GUID),
                        "name", record.get(AGENCY_ID_LIST.NAME),
                        "list_id", record.get(AGENCY_ID_LIST.LIST_ID),
                        "agency_id_list_value_guid", record.get("agency_id_list_value_guid", String.class),
                        "agency_id_list_value", record.get("agency_id_list_value", String.class),
                        "version_id", record.get(AGENCY_ID_LIST.VERSION_ID),
                        "based_agency_id_list_guid", record.get("based_agency_id_list_guid", String.class),
                        "definition", record.get(AGENCY_ID_LIST.DEFINITION),
                        "definition_source", record.get(AGENCY_ID_LIST.DEFINITION_SOURCE),
                        "remark", record.get(AGENCY_ID_LIST.REMARK),
                        "namespace_uri", record.get("namespace_uri", String.class),
                        "state", record.get(AGENCY_ID_LIST.STATE),
                        "is_deprecated", record.get(AGENCY_ID_LIST.IS_DEPRECATED),
                        "replacement_agency_id_list_guid", record.get("replacement_agency_id_list_guid", String.class),
                        "prev_agency_id_list_guid", record.get("prev_agency_id_list_guid", String.class),
                        "next_agency_id_list_guid", record.get("next_agency_id_list_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(AGENCY_ID_LIST.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(AGENCY_ID_LIST.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportAgencyIdListManifestRows(ReleaseId releaseId) {
        var agencyValueManifest = AGENCY_ID_LIST_VALUE_MANIFEST.as("agency_id_list_value_manifest");
        var agencyValue = AGENCY_ID_LIST_VALUE.as("agency_id_list_value");
        var basedManifest = AGENCY_ID_LIST_MANIFEST.as("based_agency_id_list_manifest");
        var basedAgencyList = AGENCY_ID_LIST.as("based_agency_id_list");
        var basedRelease = RELEASE.as("based_agency_id_list_release");
        var replacementManifest = AGENCY_ID_LIST_MANIFEST.as("replacement_agency_id_list_manifest");
        var replacementAgencyList = AGENCY_ID_LIST.as("replacement_agency_id_list");
        var replacementRelease = RELEASE.as("replacement_agency_id_list_release");
        var prevManifest = AGENCY_ID_LIST_MANIFEST.as("prev_agency_id_list_manifest");
        var prevAgencyList = AGENCY_ID_LIST.as("prev_agency_id_list");
        var prevRelease = RELEASE.as("prev_agency_id_list_release");
        var nextManifest = AGENCY_ID_LIST_MANIFEST.as("next_agency_id_list_manifest");
        var nextAgencyList = AGENCY_ID_LIST.as("next_agency_id_list");
        var nextRelease = RELEASE.as("next_agency_id_list_release");

        return dslContext().select(
                        AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID,
                        AGENCY_ID_LIST.GUID.as("agency_id_list_guid"),
                        agencyValue.GUID.as("agency_id_list_value_guid"),
                        agencyValue.VALUE.as("agency_id_list_value"),
                        basedAgencyList.GUID.as("based_agency_id_list_guid"),
                        basedRelease.GUID.as("based_agency_id_list_release_guid"),
                        AGENCY_ID_LIST_MANIFEST.CONFLICT,
                        replacementAgencyList.GUID.as("replacement_agency_id_list_guid"),
                        replacementRelease.GUID.as("replacement_agency_id_list_release_guid"),
                        prevAgencyList.GUID.as("prev_agency_id_list_guid"),
                        prevRelease.GUID.as("prev_agency_id_list_release_guid"),
                        nextAgencyList.GUID.as("next_agency_id_list_guid"),
                        nextRelease.GUID.as("next_agency_id_list_release_guid"))
                .from(AGENCY_ID_LIST_MANIFEST)
                .join(AGENCY_ID_LIST).on(AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID.eq(AGENCY_ID_LIST.AGENCY_ID_LIST_ID))
                .leftJoin(agencyValueManifest).on(AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID.eq(agencyValueManifest.AGENCY_ID_LIST_VALUE_MANIFEST_ID))
                .leftJoin(agencyValue).on(agencyValueManifest.AGENCY_ID_LIST_VALUE_ID.eq(agencyValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(basedManifest).on(AGENCY_ID_LIST_MANIFEST.BASED_AGENCY_ID_LIST_MANIFEST_ID.eq(basedManifest.AGENCY_ID_LIST_MANIFEST_ID))
                .leftJoin(basedAgencyList).on(basedManifest.AGENCY_ID_LIST_ID.eq(basedAgencyList.AGENCY_ID_LIST_ID))
                .leftJoin(basedRelease).on(basedManifest.RELEASE_ID.eq(basedRelease.RELEASE_ID))
                .leftJoin(replacementManifest).on(AGENCY_ID_LIST_MANIFEST.REPLACEMENT_AGENCY_ID_LIST_MANIFEST_ID.eq(replacementManifest.AGENCY_ID_LIST_MANIFEST_ID))
                .leftJoin(replacementAgencyList).on(replacementManifest.AGENCY_ID_LIST_ID.eq(replacementAgencyList.AGENCY_ID_LIST_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(AGENCY_ID_LIST_MANIFEST.PREV_AGENCY_ID_LIST_MANIFEST_ID.eq(prevManifest.AGENCY_ID_LIST_MANIFEST_ID))
                .leftJoin(prevAgencyList).on(prevManifest.AGENCY_ID_LIST_ID.eq(prevAgencyList.AGENCY_ID_LIST_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(AGENCY_ID_LIST_MANIFEST.NEXT_AGENCY_ID_LIST_MANIFEST_ID.eq(nextManifest.AGENCY_ID_LIST_MANIFEST_ID))
                .leftJoin(nextAgencyList).on(nextManifest.AGENCY_ID_LIST_ID.eq(nextAgencyList.AGENCY_ID_LIST_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(AGENCY_ID_LIST_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(AGENCY_ID_LIST.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID)),
                        "agency_id_list_guid", record.get("agency_id_list_guid", String.class),
                        "agency_id_list_value_guid", record.get("agency_id_list_value_guid", String.class),
                        "agency_id_list_value", record.get("agency_id_list_value", String.class),
                        "based_agency_id_list_guid", record.get("based_agency_id_list_guid", String.class),
                        "based_agency_id_list_release_guid", record.get("based_agency_id_list_release_guid", String.class),
                        "conflict", record.get(AGENCY_ID_LIST_MANIFEST.CONFLICT),
                        "replacement_agency_id_list_guid", record.get("replacement_agency_id_list_guid", String.class),
                        "replacement_agency_id_list_release_guid", record.get("replacement_agency_id_list_release_guid", String.class),
                        "prev_agency_id_list_guid", record.get("prev_agency_id_list_guid", String.class),
                        "prev_agency_id_list_release_guid", record.get("prev_agency_id_list_release_guid", String.class),
                        "next_agency_id_list_guid", record.get("next_agency_id_list_guid", String.class),
                        "next_agency_id_list_release_guid", record.get("next_agency_id_list_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportAgencyIdListValueRows(ReleaseId releaseId) {
        var ownerAgencyIdList = AGENCY_ID_LIST.as("owner_agency_id_list");
        var basedAgencyIdListValue = AGENCY_ID_LIST_VALUE.as("based_agency_id_list_value");
        var replacementAgencyIdListValue = AGENCY_ID_LIST_VALUE.as("replacement_agency_id_list_value");
        var prevAgencyIdListValue = AGENCY_ID_LIST_VALUE.as("prev_agency_id_list_value");
        var nextAgencyIdListValue = AGENCY_ID_LIST_VALUE.as("next_agency_id_list_value");
        var createdBy = APP_USER.as("agency_id_list_value_created_by");
        var owner = APP_USER.as("agency_id_list_value_owner");
        var lastUpdatedBy = APP_USER.as("agency_id_list_value_last_updated_by");

        return dslContext().select(
                        AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID,
                        AGENCY_ID_LIST_VALUE.GUID,
                        ownerAgencyIdList.GUID.as("agency_id_list_guid"),
                        AGENCY_ID_LIST_VALUE.VALUE,
                        AGENCY_ID_LIST_VALUE.NAME,
                        AGENCY_ID_LIST_VALUE.DEFINITION,
                        AGENCY_ID_LIST_VALUE.DEFINITION_SOURCE,
                        basedAgencyIdListValue.GUID.as("based_agency_id_list_value_guid"),
                        AGENCY_ID_LIST_VALUE.IS_DEPRECATED,
                        AGENCY_ID_LIST_VALUE.IS_DEVELOPER_DEFAULT,
                        AGENCY_ID_LIST_VALUE.IS_USER_DEFAULT,
                        replacementAgencyIdListValue.GUID.as("replacement_agency_id_list_value_guid"),
                        prevAgencyIdListValue.GUID.as("prev_agency_id_list_value_guid"),
                        nextAgencyIdListValue.GUID.as("next_agency_id_list_value_guid"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        AGENCY_ID_LIST_VALUE.CREATION_TIMESTAMP,
                        AGENCY_ID_LIST_VALUE.LAST_UPDATE_TIMESTAMP)
                .from(AGENCY_ID_LIST_VALUE_MANIFEST)
                .join(AGENCY_ID_LIST_VALUE).on(AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_ID.eq(AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID))
                .join(ownerAgencyIdList).on(AGENCY_ID_LIST_VALUE.OWNER_LIST_ID.eq(ownerAgencyIdList.AGENCY_ID_LIST_ID))
                .leftJoin(basedAgencyIdListValue).on(AGENCY_ID_LIST_VALUE.BASED_AGENCY_ID_LIST_VALUE_ID.eq(basedAgencyIdListValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(replacementAgencyIdListValue).on(AGENCY_ID_LIST_VALUE.REPLACEMENT_AGENCY_ID_LIST_VALUE_ID.eq(replacementAgencyIdListValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(prevAgencyIdListValue).on(AGENCY_ID_LIST_VALUE.PREV_AGENCY_ID_LIST_VALUE_ID.eq(prevAgencyIdListValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(nextAgencyIdListValue).on(AGENCY_ID_LIST_VALUE.NEXT_AGENCY_ID_LIST_VALUE_ID.eq(nextAgencyIdListValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(createdBy).on(AGENCY_ID_LIST_VALUE.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(AGENCY_ID_LIST_VALUE.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(AGENCY_ID_LIST_VALUE.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(AGENCY_ID_LIST_VALUE_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(AGENCY_ID_LIST_VALUE.VALUE.asc(), AGENCY_ID_LIST_VALUE.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID)),
                        "guid", record.get(AGENCY_ID_LIST_VALUE.GUID),
                        "agency_id_list_guid", record.get("agency_id_list_guid", String.class),
                        "value", record.get(AGENCY_ID_LIST_VALUE.VALUE),
                        "name", record.get(AGENCY_ID_LIST_VALUE.NAME),
                        "definition", record.get(AGENCY_ID_LIST_VALUE.DEFINITION),
                        "definition_source", record.get(AGENCY_ID_LIST_VALUE.DEFINITION_SOURCE),
                        "based_agency_id_list_value_guid", record.get("based_agency_id_list_value_guid", String.class),
                        "is_deprecated", record.get(AGENCY_ID_LIST_VALUE.IS_DEPRECATED),
                        "is_developer_default", record.get(AGENCY_ID_LIST_VALUE.IS_DEVELOPER_DEFAULT),
                        "is_user_default", record.get(AGENCY_ID_LIST_VALUE.IS_USER_DEFAULT),
                        "replacement_agency_id_list_value_guid", record.get("replacement_agency_id_list_value_guid", String.class),
                        "prev_agency_id_list_value_guid", record.get("prev_agency_id_list_value_guid", String.class),
                        "next_agency_id_list_value_guid", record.get("next_agency_id_list_value_guid", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(AGENCY_ID_LIST_VALUE.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(AGENCY_ID_LIST_VALUE.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportAgencyIdListValueManifestRows(ReleaseId releaseId) {
        var agencyIdListManifest = AGENCY_ID_LIST_MANIFEST.as("agency_id_list_manifest");
        var ownerAgencyIdList = AGENCY_ID_LIST.as("owner_agency_id_list");
        var basedManifest = AGENCY_ID_LIST_VALUE_MANIFEST.as("based_agency_id_list_value_manifest");
        var basedAgencyIdListValue = AGENCY_ID_LIST_VALUE.as("based_agency_id_list_value");
        var basedRelease = RELEASE.as("based_agency_id_list_value_release");
        var replacementManifest = AGENCY_ID_LIST_VALUE_MANIFEST.as("replacement_agency_id_list_value_manifest");
        var replacementAgencyIdListValue = AGENCY_ID_LIST_VALUE.as("replacement_agency_id_list_value");
        var replacementRelease = RELEASE.as("replacement_agency_id_list_value_release");
        var prevManifest = AGENCY_ID_LIST_VALUE_MANIFEST.as("prev_agency_id_list_value_manifest");
        var prevAgencyIdListValue = AGENCY_ID_LIST_VALUE.as("prev_agency_id_list_value");
        var prevRelease = RELEASE.as("prev_agency_id_list_value_release");
        var nextManifest = AGENCY_ID_LIST_VALUE_MANIFEST.as("next_agency_id_list_value_manifest");
        var nextAgencyIdListValue = AGENCY_ID_LIST_VALUE.as("next_agency_id_list_value");
        var nextRelease = RELEASE.as("next_agency_id_list_value_release");

        return dslContext().select(
                        AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID,
                        AGENCY_ID_LIST_VALUE.GUID.as("agency_id_list_value_guid"),
                        ownerAgencyIdList.GUID.as("agency_id_list_guid"),
                        basedAgencyIdListValue.GUID.as("based_agency_id_list_value_guid"),
                        basedRelease.GUID.as("based_agency_id_list_value_release_guid"),
                        AGENCY_ID_LIST_VALUE_MANIFEST.CONFLICT,
                        replacementAgencyIdListValue.GUID.as("replacement_agency_id_list_value_guid"),
                        replacementRelease.GUID.as("replacement_agency_id_list_value_release_guid"),
                        prevAgencyIdListValue.GUID.as("prev_agency_id_list_value_guid"),
                        prevRelease.GUID.as("prev_agency_id_list_value_release_guid"),
                        nextAgencyIdListValue.GUID.as("next_agency_id_list_value_guid"),
                        nextRelease.GUID.as("next_agency_id_list_value_release_guid"))
                .from(AGENCY_ID_LIST_VALUE_MANIFEST)
                .join(AGENCY_ID_LIST_VALUE).on(AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_ID.eq(AGENCY_ID_LIST_VALUE.AGENCY_ID_LIST_VALUE_ID))
                .join(agencyIdListManifest).on(AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID.eq(agencyIdListManifest.AGENCY_ID_LIST_MANIFEST_ID))
                .join(ownerAgencyIdList).on(agencyIdListManifest.AGENCY_ID_LIST_ID.eq(ownerAgencyIdList.AGENCY_ID_LIST_ID))
                .leftJoin(basedManifest).on(AGENCY_ID_LIST_VALUE_MANIFEST.BASED_AGENCY_ID_LIST_VALUE_MANIFEST_ID.eq(basedManifest.AGENCY_ID_LIST_VALUE_MANIFEST_ID))
                .leftJoin(basedAgencyIdListValue).on(basedManifest.AGENCY_ID_LIST_VALUE_ID.eq(basedAgencyIdListValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(basedRelease).on(basedManifest.RELEASE_ID.eq(basedRelease.RELEASE_ID))
                .leftJoin(replacementManifest).on(AGENCY_ID_LIST_VALUE_MANIFEST.REPLACEMENT_AGENCY_ID_LIST_VALUE_MANIFEST_ID.eq(replacementManifest.AGENCY_ID_LIST_VALUE_MANIFEST_ID))
                .leftJoin(replacementAgencyIdListValue).on(replacementManifest.AGENCY_ID_LIST_VALUE_ID.eq(replacementAgencyIdListValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(replacementRelease).on(replacementManifest.RELEASE_ID.eq(replacementRelease.RELEASE_ID))
                .leftJoin(prevManifest).on(AGENCY_ID_LIST_VALUE_MANIFEST.PREV_AGENCY_ID_LIST_VALUE_MANIFEST_ID.eq(prevManifest.AGENCY_ID_LIST_VALUE_MANIFEST_ID))
                .leftJoin(prevAgencyIdListValue).on(prevManifest.AGENCY_ID_LIST_VALUE_ID.eq(prevAgencyIdListValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(AGENCY_ID_LIST_VALUE_MANIFEST.NEXT_AGENCY_ID_LIST_VALUE_MANIFEST_ID.eq(nextManifest.AGENCY_ID_LIST_VALUE_MANIFEST_ID))
                .leftJoin(nextAgencyIdListValue).on(nextManifest.AGENCY_ID_LIST_VALUE_ID.eq(nextAgencyIdListValue.AGENCY_ID_LIST_VALUE_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(AGENCY_ID_LIST_VALUE_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(AGENCY_ID_LIST_VALUE.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(AGENCY_ID_LIST_VALUE_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID)),
                        "agency_id_list_value_guid", record.get("agency_id_list_value_guid", String.class),
                        "agency_id_list_guid", record.get("agency_id_list_guid", String.class),
                        "based_agency_id_list_value_guid", record.get("based_agency_id_list_value_guid", String.class),
                        "based_agency_id_list_value_release_guid", record.get("based_agency_id_list_value_release_guid", String.class),
                        "conflict", record.get(AGENCY_ID_LIST_VALUE_MANIFEST.CONFLICT),
                        "replacement_agency_id_list_value_guid", record.get("replacement_agency_id_list_value_guid", String.class),
                        "replacement_agency_id_list_value_release_guid", record.get("replacement_agency_id_list_value_release_guid", String.class),
                        "prev_agency_id_list_value_guid", record.get("prev_agency_id_list_value_guid", String.class),
                        "prev_agency_id_list_value_release_guid", record.get("prev_agency_id_list_value_release_guid", String.class),
                        "next_agency_id_list_value_guid", record.get("next_agency_id_list_value_guid", String.class),
                        "next_agency_id_list_value_release_guid", record.get("next_agency_id_list_value_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportXbtRows(ReleaseId releaseId) {
        var subtype = XBT.as("subtype_xbt");
        var createdBy = APP_USER.as("xbt_created_by");
        var owner = APP_USER.as("xbt_owner");
        var lastUpdatedBy = APP_USER.as("xbt_last_updated_by");

        return dslContext().select(
                        XBT.XBT_ID,
                        XBT.GUID,
                        XBT.NAME,
                        XBT.BUILTIN_TYPE,
                        XBT.JBT_DRAFT05_MAP,
                        XBT.JBT_202012_MAP,
                        XBT.OPENAPI30_MAP,
                        XBT.OPENAPI31_MAP,
                        XBT.AVRO_MAP,
                        subtype.GUID.as("subtype_of_xbt_guid"),
                        XBT.SCHEMA_DEFINITION,
                        XBT.REVISION_DOC,
                        XBT.STATE,
                        XBT.IS_DEPRECATED,
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        XBT.CREATION_TIMESTAMP,
                        XBT.LAST_UPDATE_TIMESTAMP)
                .from(XBT)
                .join(XBT_MANIFEST).on(XBT.XBT_ID.eq(XBT_MANIFEST.XBT_ID))
                .leftJoin(subtype).on(XBT.SUBTYPE_OF_XBT_ID.eq(subtype.XBT_ID))
                .leftJoin(createdBy).on(XBT.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(XBT.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(XBT.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(XBT_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(XBT.NAME.asc().nullsFirst(), XBT.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(XBT.XBT_ID)),
                        "guid", record.get(XBT.GUID),
                        "name", record.get(XBT.NAME),
                        "builtin_type", record.get(XBT.BUILTIN_TYPE),
                        "jbt_draft05_map", record.get(XBT.JBT_DRAFT05_MAP),
                        "jbt_202012_map", record.get(XBT.JBT_202012_MAP),
                        "openapi30_map", record.get(XBT.OPENAPI30_MAP),
                        "openapi31_map", record.get(XBT.OPENAPI31_MAP),
                        "avro_map", record.get(XBT.AVRO_MAP),
                        "subtype_of_xbt_guid", record.get("subtype_of_xbt_guid", String.class),
                        "schema_definition", record.get(XBT.SCHEMA_DEFINITION),
                        "revision_doc", record.get(XBT.REVISION_DOC),
                        "state", record.get(XBT.STATE),
                        "is_deprecated", record.get(XBT.IS_DEPRECATED),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(XBT.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(XBT.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportXbtManifestRows(ReleaseId releaseId) {
        var cdtPri = CDT_PRI.as("cdt_pri");
        var prevManifest = XBT_MANIFEST.as("prev_xbt_manifest");
        var prevXbt = XBT.as("prev_xbt");
        var prevRelease = RELEASE.as("prev_xbt_release");
        var nextManifest = XBT_MANIFEST.as("next_xbt_manifest");
        var nextXbt = XBT.as("next_xbt");
        var nextRelease = RELEASE.as("next_xbt_release");

        return dslContext().select(
                        XBT_MANIFEST.XBT_MANIFEST_ID,
                        XBT.GUID.as("xbt_guid"),
                        cdtPri.NAME.as("cdt_pri_name"),
                        XBT_MANIFEST.CONFLICT,
                        prevXbt.GUID.as("prev_xbt_guid"),
                        prevRelease.GUID.as("prev_xbt_release_guid"),
                        nextXbt.GUID.as("next_xbt_guid"),
                        nextRelease.GUID.as("next_xbt_release_guid"))
                .from(XBT_MANIFEST)
                .join(XBT).on(XBT_MANIFEST.XBT_ID.eq(XBT.XBT_ID))
                .leftJoin(cdtPri).on(XBT_MANIFEST.CDT_PRI_ID.eq(cdtPri.CDT_PRI_ID))
                .leftJoin(prevManifest).on(XBT_MANIFEST.PREV_XBT_MANIFEST_ID.eq(prevManifest.XBT_MANIFEST_ID))
                .leftJoin(prevXbt).on(prevManifest.XBT_ID.eq(prevXbt.XBT_ID))
                .leftJoin(prevRelease).on(prevManifest.RELEASE_ID.eq(prevRelease.RELEASE_ID))
                .leftJoin(nextManifest).on(XBT_MANIFEST.NEXT_XBT_MANIFEST_ID.eq(nextManifest.XBT_MANIFEST_ID))
                .leftJoin(nextXbt).on(nextManifest.XBT_ID.eq(nextXbt.XBT_ID))
                .leftJoin(nextRelease).on(nextManifest.RELEASE_ID.eq(nextRelease.RELEASE_ID))
                .where(XBT_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(XBT.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(XBT_MANIFEST.XBT_MANIFEST_ID)),
                        "xbt_guid", record.get("xbt_guid", String.class),
                        "cdt_pri_name", record.get("cdt_pri_name", String.class),
                        "conflict", record.get(XBT_MANIFEST.CONFLICT),
                        "prev_xbt_guid", record.get("prev_xbt_guid", String.class),
                        "prev_xbt_release_guid", record.get("prev_xbt_release_guid", String.class),
                        "next_xbt_guid", record.get("next_xbt_guid", String.class),
                        "next_xbt_release_guid", record.get("next_xbt_release_guid", String.class)
                ));
    }

    public List<Map<String, Object>> getExportSeqKeyRows(ReleaseId releaseId) {
        var fromAccManifest = ACC_MANIFEST.as("from_acc_manifest");
        var fromAcc = ACC.as("from_acc");
        var asccManifest = ASCC_MANIFEST.as("ascc_manifest");
        var ascc = ASCC.as("ascc");
        var bccManifest = BCC_MANIFEST.as("bcc_manifest");
        var bcc = BCC.as("bcc");
        var prevSeqKey = SEQ_KEY.as("prev_seq_key");
        var nextSeqKey = SEQ_KEY.as("next_seq_key");

        return dslContext().select(
                        SEQ_KEY.SEQ_KEY_ID,
                        fromAcc.GUID.as("from_acc_guid"),
                        ascc.GUID.as("ascc_guid"),
                        bcc.GUID.as("bcc_guid"),
                        prevSeqKey.SEQ_KEY_ID.as("prev_seq_key_id"),
                        nextSeqKey.SEQ_KEY_ID.as("next_seq_key_id"))
                .from(SEQ_KEY)
                .join(fromAccManifest).on(SEQ_KEY.FROM_ACC_MANIFEST_ID.eq(fromAccManifest.ACC_MANIFEST_ID))
                .join(fromAcc).on(fromAccManifest.ACC_ID.eq(fromAcc.ACC_ID))
                .leftJoin(asccManifest).on(SEQ_KEY.ASCC_MANIFEST_ID.eq(asccManifest.ASCC_MANIFEST_ID))
                .leftJoin(ascc).on(asccManifest.ASCC_ID.eq(ascc.ASCC_ID))
                .leftJoin(bccManifest).on(SEQ_KEY.BCC_MANIFEST_ID.eq(bccManifest.BCC_MANIFEST_ID))
                .leftJoin(bcc).on(bccManifest.BCC_ID.eq(bcc.BCC_ID))
                .leftJoin(prevSeqKey).on(SEQ_KEY.PREV_SEQ_KEY_ID.eq(prevSeqKey.SEQ_KEY_ID))
                .leftJoin(nextSeqKey).on(SEQ_KEY.NEXT_SEQ_KEY_ID.eq(nextSeqKey.SEQ_KEY_ID))
                .where(fromAccManifest.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(fromAcc.GUID.asc(), SEQ_KEY.SEQ_KEY_ID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(SEQ_KEY.SEQ_KEY_ID)),
                        "from_acc_guid", record.get("from_acc_guid", String.class),
                        "ascc_guid", record.get("ascc_guid", String.class),
                        "bcc_guid", record.get("bcc_guid", String.class),
                        "prev_seq_key_internal_id", toBigInteger(record.get("prev_seq_key_id", Number.class)),
                        "next_seq_key_internal_id", toBigInteger(record.get("next_seq_key_id", Number.class))
                ));
    }

    public List<Map<String, Object>> getExportReleaseDepRows(ReleaseId releaseId) {
        var release = RELEASE.as("release");
        var dependOnRelease = RELEASE.as("depend_on_release");
        var dependOnLibrary = LIBRARY.as("depend_on_library");
        return dslContext().select(
                        RELEASE_DEP.RELEASE_DEP_ID,
                        release.GUID.as("release_guid"),
                        release.RELEASE_NUM.as("release_num"),
                        dependOnRelease.GUID.as("depend_on_release_guid"),
                        dependOnRelease.RELEASE_NUM.as("depend_on_release_num"),
                        dependOnLibrary.NAME.as("depend_on_library_name"))
                .from(RELEASE_DEP)
                .join(release).on(RELEASE_DEP.RELEASE_ID.eq(release.RELEASE_ID))
                .join(dependOnRelease).on(RELEASE_DEP.DEPEND_ON_RELEASE_ID.eq(dependOnRelease.RELEASE_ID))
                .join(dependOnLibrary).on(dependOnRelease.LIBRARY_ID.eq(dependOnLibrary.LIBRARY_ID))
                .where(RELEASE_DEP.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(dependOnLibrary.NAME.asc(), dependOnRelease.RELEASE_NUM.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(RELEASE_DEP.RELEASE_DEP_ID)),
                        "release_guid", record.get("release_guid", String.class),
                        "release_num", record.get("release_num", String.class),
                        "depend_on_release_guid", record.get("depend_on_release_guid", String.class),
                        "depend_on_release_num", record.get("depend_on_release_num", String.class),
                        "depend_on_library_name", record.get("depend_on_library_name", String.class)
                ));
    }

    public List<Map<String, Object>> getExportTagRows(ReleaseId releaseId) {
        var createdBy = APP_USER.as("tag_created_by");
        var lastUpdatedBy = APP_USER.as("tag_last_updated_by");
        return dslContext().selectDistinct(
                        TAG.TAG_ID,
                        TAG.NAME,
                        TAG.DESCRIPTION,
                        TAG.TEXT_COLOR,
                        TAG.BACKGROUND_COLOR,
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        TAG.CREATION_TIMESTAMP,
                        TAG.LAST_UPDATE_TIMESTAMP)
                .from(TAG)
                .leftJoin(createdBy).on(TAG.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(TAG.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(TAG.TAG_ID.in(
                        dslContext().select(ACC_MANIFEST_TAG.TAG_ID).from(ACC_MANIFEST_TAG)
                                .join(ACC_MANIFEST).on(ACC_MANIFEST_TAG.ACC_MANIFEST_ID.eq(ACC_MANIFEST.ACC_MANIFEST_ID))
                                .where(ACC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                                .union(
                                        dslContext().select(ASCCP_MANIFEST_TAG.TAG_ID).from(ASCCP_MANIFEST_TAG)
                                                .join(ASCCP_MANIFEST).on(ASCCP_MANIFEST_TAG.ASCCP_MANIFEST_ID.eq(ASCCP_MANIFEST.ASCCP_MANIFEST_ID))
                                                .where(ASCCP_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                                ).union(
                                        dslContext().select(BCCP_MANIFEST_TAG.TAG_ID).from(BCCP_MANIFEST_TAG)
                                                .join(BCCP_MANIFEST).on(BCCP_MANIFEST_TAG.BCCP_MANIFEST_ID.eq(BCCP_MANIFEST.BCCP_MANIFEST_ID))
                                                .where(BCCP_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                                ).union(
                                        dslContext().select(DT_MANIFEST_TAG.TAG_ID).from(DT_MANIFEST_TAG)
                                                .join(DT_MANIFEST).on(DT_MANIFEST_TAG.DT_MANIFEST_ID.eq(DT_MANIFEST.DT_MANIFEST_ID))
                                                .where(DT_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                                )))
                .orderBy(TAG.NAME.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(TAG.TAG_ID)),
                        "name", record.get(TAG.NAME),
                        "description", record.get(TAG.DESCRIPTION),
                        "text_color", record.get(TAG.TEXT_COLOR),
                        "background_color", record.get(TAG.BACKGROUND_COLOR),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(TAG.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(TAG.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportAccManifestTagRows(ReleaseId releaseId) {
        var createdBy = APP_USER.as("acc_manifest_tag_created_by");
        return dslContext().select(
                        ACC_MANIFEST_TAG.ACC_MANIFEST_ID,
                        ACC_MANIFEST_TAG.TAG_ID,
                        ACC.GUID.as("acc_guid"),
                        TAG.NAME.as("tag_name"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        ACC_MANIFEST_TAG.CREATION_TIMESTAMP)
                .from(ACC_MANIFEST_TAG)
                .join(ACC_MANIFEST).on(ACC_MANIFEST_TAG.ACC_MANIFEST_ID.eq(ACC_MANIFEST.ACC_MANIFEST_ID))
                .join(ACC).on(ACC_MANIFEST.ACC_ID.eq(ACC.ACC_ID))
                .join(TAG).on(ACC_MANIFEST_TAG.TAG_ID.eq(TAG.TAG_ID))
                .leftJoin(createdBy).on(ACC_MANIFEST_TAG.CREATED_BY.eq(createdBy.APP_USER_ID))
                .where(ACC_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(ACC.GUID.asc(), TAG.NAME.asc())
                .fetch(record -> payloadRowWithInternalId(
                        payloadRow(
                                "acc_manifest_id", toBigInteger(record.get(ACC_MANIFEST_TAG.ACC_MANIFEST_ID)),
                                "tag_id", toBigInteger(record.get(ACC_MANIFEST_TAG.TAG_ID))
                        ),
                        "acc_guid", record.get("acc_guid", String.class),
                        "tag_name", record.get("tag_name", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(ACC_MANIFEST_TAG.CREATION_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportAsccpManifestTagRows(ReleaseId releaseId) {
        var createdBy = APP_USER.as("asccp_manifest_tag_created_by");
        return dslContext().select(
                        ASCCP_MANIFEST_TAG.ASCCP_MANIFEST_ID,
                        ASCCP_MANIFEST_TAG.TAG_ID,
                        ASCCP.GUID.as("asccp_guid"),
                        TAG.NAME.as("tag_name"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        ASCCP_MANIFEST_TAG.CREATION_TIMESTAMP)
                .from(ASCCP_MANIFEST_TAG)
                .join(ASCCP_MANIFEST).on(ASCCP_MANIFEST_TAG.ASCCP_MANIFEST_ID.eq(ASCCP_MANIFEST.ASCCP_MANIFEST_ID))
                .join(ASCCP).on(ASCCP_MANIFEST.ASCCP_ID.eq(ASCCP.ASCCP_ID))
                .join(TAG).on(ASCCP_MANIFEST_TAG.TAG_ID.eq(TAG.TAG_ID))
                .leftJoin(createdBy).on(ASCCP_MANIFEST_TAG.CREATED_BY.eq(createdBy.APP_USER_ID))
                .where(ASCCP_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(ASCCP.GUID.asc(), TAG.NAME.asc())
                .fetch(record -> payloadRowWithInternalId(
                        payloadRow(
                                "asccp_manifest_id", toBigInteger(record.get(ASCCP_MANIFEST_TAG.ASCCP_MANIFEST_ID)),
                                "tag_id", toBigInteger(record.get(ASCCP_MANIFEST_TAG.TAG_ID))
                        ),
                        "asccp_guid", record.get("asccp_guid", String.class),
                        "tag_name", record.get("tag_name", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(ASCCP_MANIFEST_TAG.CREATION_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportBccpManifestTagRows(ReleaseId releaseId) {
        var createdBy = APP_USER.as("bccp_manifest_tag_created_by");
        return dslContext().select(
                        BCCP_MANIFEST_TAG.BCCP_MANIFEST_ID,
                        BCCP_MANIFEST_TAG.TAG_ID,
                        BCCP.GUID.as("bccp_guid"),
                        TAG.NAME.as("tag_name"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        BCCP_MANIFEST_TAG.CREATION_TIMESTAMP)
                .from(BCCP_MANIFEST_TAG)
                .join(BCCP_MANIFEST).on(BCCP_MANIFEST_TAG.BCCP_MANIFEST_ID.eq(BCCP_MANIFEST.BCCP_MANIFEST_ID))
                .join(BCCP).on(BCCP_MANIFEST.BCCP_ID.eq(BCCP.BCCP_ID))
                .join(TAG).on(BCCP_MANIFEST_TAG.TAG_ID.eq(TAG.TAG_ID))
                .leftJoin(createdBy).on(BCCP_MANIFEST_TAG.CREATED_BY.eq(createdBy.APP_USER_ID))
                .where(BCCP_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(BCCP.GUID.asc(), TAG.NAME.asc())
                .fetch(record -> payloadRowWithInternalId(
                        payloadRow(
                                "bccp_manifest_id", toBigInteger(record.get(BCCP_MANIFEST_TAG.BCCP_MANIFEST_ID)),
                                "tag_id", toBigInteger(record.get(BCCP_MANIFEST_TAG.TAG_ID))
                        ),
                        "bccp_guid", record.get("bccp_guid", String.class),
                        "tag_name", record.get("tag_name", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(BCCP_MANIFEST_TAG.CREATION_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportDtManifestTagRows(ReleaseId releaseId) {
        var createdBy = APP_USER.as("dt_manifest_tag_created_by");
        return dslContext().select(
                        DT_MANIFEST_TAG.DT_MANIFEST_ID,
                        DT_MANIFEST_TAG.TAG_ID,
                        DT.GUID.as("dt_guid"),
                        TAG.NAME.as("tag_name"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        DT_MANIFEST_TAG.CREATION_TIMESTAMP)
                .from(DT_MANIFEST_TAG)
                .join(DT_MANIFEST).on(DT_MANIFEST_TAG.DT_MANIFEST_ID.eq(DT_MANIFEST.DT_MANIFEST_ID))
                .join(DT).on(DT_MANIFEST.DT_ID.eq(DT.DT_ID))
                .join(TAG).on(DT_MANIFEST_TAG.TAG_ID.eq(TAG.TAG_ID))
                .leftJoin(createdBy).on(DT_MANIFEST_TAG.CREATED_BY.eq(createdBy.APP_USER_ID))
                .where(DT_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(DT.GUID.asc(), TAG.NAME.asc())
                .fetch(record -> payloadRowWithInternalId(
                        payloadRow(
                                "dt_manifest_id", toBigInteger(record.get(DT_MANIFEST_TAG.DT_MANIFEST_ID)),
                                "tag_id", toBigInteger(record.get(DT_MANIFEST_TAG.TAG_ID))
                        ),
                        "dt_guid", record.get("dt_guid", String.class),
                        "tag_name", record.get("tag_name", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(DT_MANIFEST_TAG.CREATION_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportModuleSetRows(ReleaseId releaseId) {
        var createdBy = APP_USER.as("module_set_created_by");
        var lastUpdatedBy = APP_USER.as("module_set_last_updated_by");
        return dslContext().selectDistinct(
                        MODULE_SET.MODULE_SET_ID,
                        MODULE_SET.GUID,
                        MODULE_SET.NAME,
                        MODULE_SET.DESCRIPTION,
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        MODULE_SET.CREATION_TIMESTAMP,
                        MODULE_SET.LAST_UPDATE_TIMESTAMP)
                .from(MODULE_SET)
                .join(MODULE_SET_RELEASE).on(MODULE_SET.MODULE_SET_ID.eq(MODULE_SET_RELEASE.MODULE_SET_ID))
                .leftJoin(createdBy).on(MODULE_SET.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(MODULE_SET.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(MODULE_SET_RELEASE.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(MODULE_SET.NAME.asc(), MODULE_SET.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(MODULE_SET.MODULE_SET_ID)),
                        "guid", record.get(MODULE_SET.GUID),
                        "name", record.get(MODULE_SET.NAME),
                        "description", record.get(MODULE_SET.DESCRIPTION),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(MODULE_SET.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(MODULE_SET.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportModuleSetReleaseRows(ReleaseId releaseId) {
        var createdBy = APP_USER.as("module_set_release_created_by");
        var lastUpdatedBy = APP_USER.as("module_set_release_last_updated_by");
        return dslContext().select(
                        MODULE_SET_RELEASE.MODULE_SET_RELEASE_ID,
                        MODULE_SET.GUID.as("module_set_guid"),
                        RELEASE.GUID.as("release_guid"),
                        MODULE_SET_RELEASE.NAME,
                        MODULE_SET_RELEASE.DESCRIPTION,
                        MODULE_SET_RELEASE.IS_DEFAULT,
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        MODULE_SET_RELEASE.CREATION_TIMESTAMP,
                        MODULE_SET_RELEASE.LAST_UPDATE_TIMESTAMP)
                .from(MODULE_SET_RELEASE)
                .join(MODULE_SET).on(MODULE_SET_RELEASE.MODULE_SET_ID.eq(MODULE_SET.MODULE_SET_ID))
                .join(RELEASE).on(MODULE_SET_RELEASE.RELEASE_ID.eq(RELEASE.RELEASE_ID))
                .leftJoin(createdBy).on(MODULE_SET_RELEASE.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(MODULE_SET_RELEASE.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(MODULE_SET_RELEASE.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(MODULE_SET_RELEASE.NAME.asc(), MODULE_SET.GUID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(MODULE_SET_RELEASE.MODULE_SET_RELEASE_ID)),
                        "module_set_guid", record.get("module_set_guid", String.class),
                        "release_guid", record.get("release_guid", String.class),
                        "name", record.get(MODULE_SET_RELEASE.NAME),
                        "description", record.get(MODULE_SET_RELEASE.DESCRIPTION),
                        "is_default", record.get(MODULE_SET_RELEASE.IS_DEFAULT),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(MODULE_SET_RELEASE.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(MODULE_SET_RELEASE.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportModuleRows(ReleaseId releaseId) {
        var parentModule = MODULE.as("parent_module");
        var namespace = NAMESPACE.as("module_namespace");
        var createdBy = APP_USER.as("module_created_by");
        var owner = APP_USER.as("module_owner");
        var lastUpdatedBy = APP_USER.as("module_last_updated_by");
        return dslContext().selectDistinct(
                        MODULE.MODULE_ID,
                        MODULE_SET.GUID.as("module_set_guid"),
                        MODULE.TYPE,
                        MODULE.PATH,
                        MODULE.NAME,
                        namespace.URI.as("namespace_uri"),
                        MODULE.VERSION_NUM,
                        parentModule.PATH.as("parent_module_path"),
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        owner.LOGIN_ID.as("owner_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        MODULE.CREATION_TIMESTAMP,
                        MODULE.LAST_UPDATE_TIMESTAMP)
                .from(MODULE)
                .join(MODULE_SET).on(MODULE.MODULE_SET_ID.eq(MODULE_SET.MODULE_SET_ID))
                .join(MODULE_SET_RELEASE).on(MODULE_SET.MODULE_SET_ID.eq(MODULE_SET_RELEASE.MODULE_SET_ID))
                .leftJoin(namespace).on(MODULE.NAMESPACE_ID.eq(namespace.NAMESPACE_ID))
                .leftJoin(parentModule).on(MODULE.PARENT_MODULE_ID.eq(parentModule.MODULE_ID))
                .leftJoin(createdBy).on(MODULE.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(owner).on(MODULE.OWNER_USER_ID.eq(owner.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(MODULE.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(MODULE_SET_RELEASE.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(MODULE.PATH.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(MODULE.MODULE_ID)),
                        "module_set_guid", record.get("module_set_guid", String.class),
                        "type", record.get(MODULE.TYPE),
                        "path", record.get(MODULE.PATH),
                        "name", record.get(MODULE.NAME),
                        "namespace_uri", record.get("namespace_uri", String.class),
                        "version_num", record.get(MODULE.VERSION_NUM),
                        "parent_module_path", record.get("parent_module_path", String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "owner_login_id", record.get("owner_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(MODULE.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(MODULE.LAST_UPDATE_TIMESTAMP))
                ));
    }

    public List<Map<String, Object>> getExportModuleAccManifestRows(ReleaseId releaseId) {
        return getModuleManifestRows(releaseId, MODULE_ACC_MANIFEST, MODULE_ACC_MANIFEST.MODULE_ACC_MANIFEST_ID, MODULE_ACC_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_ACC_MANIFEST.MODULE_ID, MODULE_ACC_MANIFEST.CREATED_BY, MODULE_ACC_MANIFEST.LAST_UPDATED_BY,
                MODULE_ACC_MANIFEST.CREATION_TIMESTAMP, MODULE_ACC_MANIFEST.LAST_UPDATE_TIMESTAMP,
                MODULE_ACC_MANIFEST.ACC_MANIFEST_ID,
                ACC_MANIFEST, ACC_MANIFEST.ACC_MANIFEST_ID, ACC_MANIFEST.ACC_ID, ACC, ACC.ACC_ID, ACC.GUID,
                "acc_guid");
    }

    public List<Map<String, Object>> getExportModuleAgencyIdListManifestRows(ReleaseId releaseId) {
        return getModuleManifestRows(releaseId, MODULE_AGENCY_ID_LIST_MANIFEST, MODULE_AGENCY_ID_LIST_MANIFEST.MODULE_AGENCY_ID_LIST_MANIFEST_ID, MODULE_AGENCY_ID_LIST_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_AGENCY_ID_LIST_MANIFEST.MODULE_ID, MODULE_AGENCY_ID_LIST_MANIFEST.CREATED_BY, MODULE_AGENCY_ID_LIST_MANIFEST.LAST_UPDATED_BY,
                MODULE_AGENCY_ID_LIST_MANIFEST.CREATION_TIMESTAMP, MODULE_AGENCY_ID_LIST_MANIFEST.LAST_UPDATE_TIMESTAMP,
                MODULE_AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID,
                AGENCY_ID_LIST_MANIFEST, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID, AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID,
                AGENCY_ID_LIST, AGENCY_ID_LIST.AGENCY_ID_LIST_ID, AGENCY_ID_LIST.GUID, "agency_id_list_guid");
    }

    public List<Map<String, Object>> getExportModuleAsccpManifestRows(ReleaseId releaseId) {
        return getModuleManifestRows(releaseId, MODULE_ASCCP_MANIFEST, MODULE_ASCCP_MANIFEST.MODULE_ASCCP_MANIFEST_ID, MODULE_ASCCP_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_ASCCP_MANIFEST.MODULE_ID, MODULE_ASCCP_MANIFEST.CREATED_BY, MODULE_ASCCP_MANIFEST.LAST_UPDATED_BY,
                MODULE_ASCCP_MANIFEST.CREATION_TIMESTAMP, MODULE_ASCCP_MANIFEST.LAST_UPDATE_TIMESTAMP,
                MODULE_ASCCP_MANIFEST.ASCCP_MANIFEST_ID,
                ASCCP_MANIFEST, ASCCP_MANIFEST.ASCCP_MANIFEST_ID, ASCCP_MANIFEST.ASCCP_ID, ASCCP, ASCCP.ASCCP_ID, ASCCP.GUID,
                "asccp_guid");
    }

    public List<Map<String, Object>> getExportModuleBccpManifestRows(ReleaseId releaseId) {
        return getModuleManifestRows(releaseId, MODULE_BCCP_MANIFEST, MODULE_BCCP_MANIFEST.MODULE_BCCP_MANIFEST_ID, MODULE_BCCP_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_BCCP_MANIFEST.MODULE_ID, MODULE_BCCP_MANIFEST.CREATED_BY, MODULE_BCCP_MANIFEST.LAST_UPDATED_BY,
                MODULE_BCCP_MANIFEST.CREATION_TIMESTAMP, MODULE_BCCP_MANIFEST.LAST_UPDATE_TIMESTAMP,
                MODULE_BCCP_MANIFEST.BCCP_MANIFEST_ID,
                BCCP_MANIFEST, BCCP_MANIFEST.BCCP_MANIFEST_ID, BCCP_MANIFEST.BCCP_ID, BCCP, BCCP.BCCP_ID, BCCP.GUID,
                "bccp_guid");
    }

    public List<Map<String, Object>> getExportModuleCodeListManifestRows(ReleaseId releaseId) {
        return getModuleManifestRows(releaseId, MODULE_CODE_LIST_MANIFEST, MODULE_CODE_LIST_MANIFEST.MODULE_CODE_LIST_MANIFEST_ID, MODULE_CODE_LIST_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_CODE_LIST_MANIFEST.MODULE_ID, MODULE_CODE_LIST_MANIFEST.CREATED_BY, MODULE_CODE_LIST_MANIFEST.LAST_UPDATED_BY,
                MODULE_CODE_LIST_MANIFEST.CREATION_TIMESTAMP, MODULE_CODE_LIST_MANIFEST.LAST_UPDATE_TIMESTAMP,
                MODULE_CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID,
                CODE_LIST_MANIFEST, CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID, CODE_LIST_MANIFEST.CODE_LIST_ID, CODE_LIST, CODE_LIST.CODE_LIST_ID, CODE_LIST.GUID,
                "code_list_guid");
    }

    public List<Map<String, Object>> getExportModuleDtManifestRows(ReleaseId releaseId) {
        return getModuleManifestRows(releaseId, MODULE_DT_MANIFEST, MODULE_DT_MANIFEST.MODULE_DT_MANIFEST_ID, MODULE_DT_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_DT_MANIFEST.MODULE_ID, MODULE_DT_MANIFEST.CREATED_BY, MODULE_DT_MANIFEST.LAST_UPDATED_BY,
                MODULE_DT_MANIFEST.CREATION_TIMESTAMP, MODULE_DT_MANIFEST.LAST_UPDATE_TIMESTAMP,
                MODULE_DT_MANIFEST.DT_MANIFEST_ID,
                DT_MANIFEST, DT_MANIFEST.DT_MANIFEST_ID, DT_MANIFEST.DT_ID, DT, DT.DT_ID, DT.GUID,
                "dt_guid");
    }

    public List<Map<String, Object>> getExportModuleXbtManifestRows(ReleaseId releaseId) {
        return getModuleManifestRows(releaseId, MODULE_XBT_MANIFEST, MODULE_XBT_MANIFEST.MODULE_XBT_MANIFEST_ID, MODULE_XBT_MANIFEST.MODULE_SET_RELEASE_ID,
                MODULE_XBT_MANIFEST.MODULE_ID, MODULE_XBT_MANIFEST.CREATED_BY, MODULE_XBT_MANIFEST.LAST_UPDATED_BY,
                MODULE_XBT_MANIFEST.CREATION_TIMESTAMP, MODULE_XBT_MANIFEST.LAST_UPDATE_TIMESTAMP,
                MODULE_XBT_MANIFEST.XBT_MANIFEST_ID,
                XBT_MANIFEST, XBT_MANIFEST.XBT_MANIFEST_ID, XBT_MANIFEST.XBT_ID, XBT, XBT.XBT_ID, XBT.GUID,
                "xbt_guid");
    }

    public List<Map<String, Object>> getExportBlobContentRows(ReleaseId releaseId) {
        return dslContext().select(
                        BLOB_CONTENT.BLOB_CONTENT_ID,
                        BLOB_CONTENT.CONTENT)
                .from(BLOB_CONTENT_MANIFEST)
                .join(BLOB_CONTENT).on(BLOB_CONTENT_MANIFEST.BLOB_CONTENT_ID.eq(BLOB_CONTENT.BLOB_CONTENT_ID))
                .where(BLOB_CONTENT_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(BLOB_CONTENT.BLOB_CONTENT_ID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(BLOB_CONTENT.BLOB_CONTENT_ID)),
                        "content", record.get(BLOB_CONTENT.CONTENT)
                ));
    }

    public List<Map<String, Object>> getExportBlobContentManifestRows(ReleaseId releaseId) {
        var prevBlobContentManifest = BLOB_CONTENT_MANIFEST.as("prev_blob_content_manifest");
        var nextBlobContentManifest = BLOB_CONTENT_MANIFEST.as("next_blob_content_manifest");
        return dslContext().select(
                        BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID,
                        BLOB_CONTENT_MANIFEST.BLOB_CONTENT_ID,
                        BLOB_CONTENT_MANIFEST.CONFLICT,
                        prevBlobContentManifest.BLOB_CONTENT_MANIFEST_ID.as("prev_blob_content_manifest_id"),
                        nextBlobContentManifest.BLOB_CONTENT_MANIFEST_ID.as("next_blob_content_manifest_id"))
                .from(BLOB_CONTENT_MANIFEST)
                .leftJoin(prevBlobContentManifest).on(BLOB_CONTENT_MANIFEST.PREV_BLOB_CONTENT_MANIFEST_ID.eq(prevBlobContentManifest.BLOB_CONTENT_MANIFEST_ID))
                .leftJoin(nextBlobContentManifest).on(BLOB_CONTENT_MANIFEST.NEXT_BLOB_CONTENT_MANIFEST_ID.eq(nextBlobContentManifest.BLOB_CONTENT_MANIFEST_ID))
                .where(BLOB_CONTENT_MANIFEST.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID)),
                        "blob_content_internal_id", toBigInteger(record.get(BLOB_CONTENT_MANIFEST.BLOB_CONTENT_ID)),
                        "conflict", record.get(BLOB_CONTENT_MANIFEST.CONFLICT),
                        "prev_blob_content_manifest_internal_id", toBigInteger(record.get("prev_blob_content_manifest_id", Number.class)),
                        "next_blob_content_manifest_internal_id", toBigInteger(record.get("next_blob_content_manifest_id", Number.class))
                ));
    }

    public List<Map<String, Object>> getExportModuleBlobContentManifestRows(ReleaseId releaseId) {
        var createdBy = APP_USER.as("module_blob_content_manifest_created_by");
        var lastUpdatedBy = APP_USER.as("module_blob_content_manifest_last_updated_by");
        return dslContext().select(
                        MODULE_BLOB_CONTENT_MANIFEST.MODULE_BLOB_CONTENT_MANIFEST_ID,
                        MODULE_SET.GUID.as("module_set_guid"),
                        MODULE_SET_RELEASE.NAME.as("module_set_release_name"),
                        MODULE.PATH.as("module_path"),
                        MODULE_BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID,
                        createdBy.LOGIN_ID.as("created_by_login_id"),
                        lastUpdatedBy.LOGIN_ID.as("last_updated_by_login_id"),
                        MODULE_BLOB_CONTENT_MANIFEST.CREATION_TIMESTAMP,
                        MODULE_BLOB_CONTENT_MANIFEST.LAST_UPDATE_TIMESTAMP)
                .from(MODULE_BLOB_CONTENT_MANIFEST)
                .join(MODULE_SET_RELEASE).on(MODULE_BLOB_CONTENT_MANIFEST.MODULE_SET_RELEASE_ID.eq(MODULE_SET_RELEASE.MODULE_SET_RELEASE_ID))
                .join(MODULE_SET).on(MODULE_SET_RELEASE.MODULE_SET_ID.eq(MODULE_SET.MODULE_SET_ID))
                .join(MODULE).on(MODULE_BLOB_CONTENT_MANIFEST.MODULE_ID.eq(MODULE.MODULE_ID))
                .leftJoin(createdBy).on(MODULE_BLOB_CONTENT_MANIFEST.CREATED_BY.eq(createdBy.APP_USER_ID))
                .leftJoin(lastUpdatedBy).on(MODULE_BLOB_CONTENT_MANIFEST.LAST_UPDATED_BY.eq(lastUpdatedBy.APP_USER_ID))
                .where(MODULE_SET_RELEASE.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(MODULE.PATH.asc(), MODULE_BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(MODULE_BLOB_CONTENT_MANIFEST.MODULE_BLOB_CONTENT_MANIFEST_ID)),
                        "module_set_guid", record.get("module_set_guid", String.class),
                        "module_set_release_name", record.get("module_set_release_name", String.class),
                        "module_path", record.get("module_path", String.class),
                        "blob_content_manifest_internal_id", toBigInteger(record.get(MODULE_BLOB_CONTENT_MANIFEST.BLOB_CONTENT_MANIFEST_ID)),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(MODULE_BLOB_CONTENT_MANIFEST.CREATION_TIMESTAMP)),
                        "last_update_timestamp", toTimestamp(record.get(MODULE_BLOB_CONTENT_MANIFEST.LAST_UPDATE_TIMESTAMP))
                ));
    }

    private List<Map<String, Object>> getModuleManifestRows(
            ReleaseId releaseId,
            Table<? extends UpdatableRecord<?>> moduleManifestTable,
            TableField<? extends Record, ULong> moduleManifestIdField,
            TableField<? extends Record, ULong> moduleSetReleaseIdField,
            TableField<? extends Record, ULong> moduleIdField,
            TableField<? extends Record, ULong> createdByField,
            TableField<? extends Record, ULong> lastUpdatedByField,
            TableField<? extends Record, LocalDateTime> creationTimestampField,
            TableField<? extends Record, LocalDateTime> lastUpdateTimestampField,
            TableField<? extends Record, ULong> manifestFkField,
            Table<? extends UpdatableRecord<?>> manifestTable,
            TableField<? extends Record, ULong> manifestPkField,
            TableField<? extends Record, ULong> rawFkField,
            Table<? extends UpdatableRecord<?>> rawTable,
            TableField<? extends Record, ULong> rawPkField,
            TableField<? extends Record, String> rawGuidField,
            String rawGuidKey) {

        var moduleSetGuid = MODULE_SET.GUID.as("module_set_guid");
        var moduleSetReleaseName = MODULE_SET_RELEASE.NAME.as("module_set_release_name");
        var modulePath = MODULE.PATH.as("module_path");
        var createdByLoginId = APP_USER.as("module_manifest_created_by").LOGIN_ID.as("created_by_login_id");
        var lastUpdatedByLoginId = APP_USER.as("module_manifest_last_updated_by").LOGIN_ID.as("last_updated_by_login_id");

        return dslContext().select(
                        moduleManifestIdField,
                        moduleSetGuid,
                        moduleSetReleaseName,
                        modulePath,
                        rawGuidField.as(rawGuidKey),
                        createdByLoginId,
                        lastUpdatedByLoginId,
                        creationTimestampField,
                        lastUpdateTimestampField)
                .from(moduleManifestTable)
                .join(MODULE_SET_RELEASE).on(moduleSetReleaseIdField.eq(MODULE_SET_RELEASE.MODULE_SET_RELEASE_ID))
                .join(MODULE_SET).on(MODULE_SET_RELEASE.MODULE_SET_ID.eq(MODULE_SET.MODULE_SET_ID))
                .join(MODULE).on(moduleIdField.eq(MODULE.MODULE_ID))
                .join(manifestTable).on(manifestFkField.eq(manifestPkField))
                .join(rawTable).on(manifestTable.field(rawFkField.getName(), ULong.class).eq(rawPkField))
                .leftJoin(APP_USER.as("module_manifest_created_by")).on(createdByField.eq(APP_USER.as("module_manifest_created_by").APP_USER_ID))
                .leftJoin(APP_USER.as("module_manifest_last_updated_by")).on(lastUpdatedByField.eq(APP_USER.as("module_manifest_last_updated_by").APP_USER_ID))
                .where(MODULE_SET_RELEASE.RELEASE_ID.eq(ULong.valueOf(releaseId.value())))
                .orderBy(MODULE.PATH.asc(), rawGuidField.asc())
                .fetch(record -> payloadRowWithInternalId(
                        toBigInteger(record.get(moduleManifestIdField)),
                        "module_set_guid", record.get("module_set_guid", String.class),
                        "module_set_release_name", record.get("module_set_release_name", String.class),
                        "module_path", record.get("module_path", String.class),
                        rawGuidKey, record.get(rawGuidKey, String.class),
                        "created_by_login_id", record.get("created_by_login_id", String.class),
                        "last_updated_by_login_id", record.get("last_updated_by_login_id", String.class),
                        "creation_timestamp", toTimestamp(record.get(creationTimestampField)),
                        "last_update_timestamp", toTimestamp(record.get(lastUpdateTimestampField))
                ));
    }

}

package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Record1;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.model.AiOwnedEntityKind;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChangeOwnershipQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Abie.ABIE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Acc.ACC;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AccManifest.ACC_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Asbie.ASBIE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Ascc.ASCC;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AsccManifest.ASCC_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Asccp.ASCCP;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AsccpManifest.ASCCP_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Bbie.BBIE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.BbieSc.BBIE_SC;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Bcc.BCC;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.BccManifest.BCC_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Bccp.BCCP;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.BccpManifest.BCCP_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.BizCtx.BIZ_CTX;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.BizCtxValue.BIZ_CTX_VALUE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.CodeList.CODE_LIST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.CodeListManifest.CODE_LIST_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.CodeListValue.CODE_LIST_VALUE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.CodeListValueManifest.CODE_LIST_VALUE_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.CtxCategory.CTX_CATEGORY;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.CtxScheme.CTX_SCHEME;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.CtxSchemeValue.CTX_SCHEME_VALUE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Dt.DT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.DtManifest.DT_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.DtSc.DT_SC;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.DtScManifest.DT_SC_MANIFEST;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Library.LIBRARY;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.Namespace.NAMESPACE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.TopLevelAsbiep.TOP_LEVEL_ASBIEP;

/**
 * JOOQ read-side implementation of {@link AiChangeOwnershipQueryRepository}.
 *
 * <p>Each kind resolves through the same join the owning screen uses: core components and code
 * lists carry {@code owner_user_id} on the base record behind their manifest, BIE nodes inherit
 * the owner of their top-level ASBIEP, and context records, which have no owner column, are
 * owned by their creator.
 */
public class JooqAiChangeOwnershipQueryRepository extends JooqBaseRepository
        implements AiChangeOwnershipQueryRepository {

    /**
     * Creates a requester-bound repository with the application JOOQ context.
     *
     * @param dslContext context used to execute generated-model queries
     * @param requester signed-in conversation owner
     * @param repositoryFactory factory for related database repositories
     */
    public JooqAiChangeOwnershipQueryRepository(
            DSLContext dslContext, ScoreUser requester, RepositoryFactory repositoryFactory) {
        super(dslContext, Objects.requireNonNull(requester, "requester"), repositoryFactory);
    }

    @Override
    public Optional<UserId> findOwner(AiOwnedEntityKind kind, BigInteger id) {
        if (kind == null || id == null || id.signum() <= 0) {
            return Optional.empty();
        }
        return owner(kind, ULong.valueOf(id)).fetchOptional()
                .map(Record1::value1)
                .map(ULong::toBigInteger)
                .map(UserId::new);
    }

    private SelectConditionStep<Record1<ULong>> owner(AiOwnedEntityKind kind, ULong id) {
        return switch (kind) {
            case TOP_LEVEL_ASBIEP -> dslContext().select(TOP_LEVEL_ASBIEP.OWNER_USER_ID)
                    .from(TOP_LEVEL_ASBIEP)
                    .where(TOP_LEVEL_ASBIEP.TOP_LEVEL_ASBIEP_ID.eq(id));
            case ABIE -> dslContext().select(TOP_LEVEL_ASBIEP.OWNER_USER_ID)
                    .from(ABIE)
                    .join(TOP_LEVEL_ASBIEP)
                    .on(TOP_LEVEL_ASBIEP.TOP_LEVEL_ASBIEP_ID.eq(ABIE.OWNER_TOP_LEVEL_ASBIEP_ID))
                    .where(ABIE.ABIE_ID.eq(id));
            case ASBIE -> dslContext().select(TOP_LEVEL_ASBIEP.OWNER_USER_ID)
                    .from(ASBIE)
                    .join(TOP_LEVEL_ASBIEP)
                    .on(TOP_LEVEL_ASBIEP.TOP_LEVEL_ASBIEP_ID.eq(ASBIE.OWNER_TOP_LEVEL_ASBIEP_ID))
                    .where(ASBIE.ASBIE_ID.eq(id));
            case BBIE -> dslContext().select(TOP_LEVEL_ASBIEP.OWNER_USER_ID)
                    .from(BBIE)
                    .join(TOP_LEVEL_ASBIEP)
                    .on(TOP_LEVEL_ASBIEP.TOP_LEVEL_ASBIEP_ID.eq(BBIE.OWNER_TOP_LEVEL_ASBIEP_ID))
                    .where(BBIE.BBIE_ID.eq(id));
            case BBIE_SC -> dslContext().select(TOP_LEVEL_ASBIEP.OWNER_USER_ID)
                    .from(BBIE_SC)
                    .join(TOP_LEVEL_ASBIEP)
                    .on(TOP_LEVEL_ASBIEP.TOP_LEVEL_ASBIEP_ID.eq(BBIE_SC.OWNER_TOP_LEVEL_ASBIEP_ID))
                    .where(BBIE_SC.BBIE_SC_ID.eq(id));
            case ACC_MANIFEST -> dslContext().select(ACC.OWNER_USER_ID)
                    .from(ACC_MANIFEST)
                    .join(ACC).on(ACC.ACC_ID.eq(ACC_MANIFEST.ACC_ID))
                    .where(ACC_MANIFEST.ACC_MANIFEST_ID.eq(id));
            case ASCCP_MANIFEST -> dslContext().select(ASCCP.OWNER_USER_ID)
                    .from(ASCCP_MANIFEST)
                    .join(ASCCP).on(ASCCP.ASCCP_ID.eq(ASCCP_MANIFEST.ASCCP_ID))
                    .where(ASCCP_MANIFEST.ASCCP_MANIFEST_ID.eq(id));
            case BCCP_MANIFEST -> dslContext().select(BCCP.OWNER_USER_ID)
                    .from(BCCP_MANIFEST)
                    .join(BCCP).on(BCCP.BCCP_ID.eq(BCCP_MANIFEST.BCCP_ID))
                    .where(BCCP_MANIFEST.BCCP_MANIFEST_ID.eq(id));
            case ASCC_MANIFEST -> dslContext().select(ASCC.OWNER_USER_ID)
                    .from(ASCC_MANIFEST)
                    .join(ASCC).on(ASCC.ASCC_ID.eq(ASCC_MANIFEST.ASCC_ID))
                    .where(ASCC_MANIFEST.ASCC_MANIFEST_ID.eq(id));
            case BCC_MANIFEST -> dslContext().select(BCC.OWNER_USER_ID)
                    .from(BCC_MANIFEST)
                    .join(BCC).on(BCC.BCC_ID.eq(BCC_MANIFEST.BCC_ID))
                    .where(BCC_MANIFEST.BCC_MANIFEST_ID.eq(id));
            case DT_MANIFEST -> dslContext().select(DT.OWNER_USER_ID)
                    .from(DT_MANIFEST)
                    .join(DT).on(DT.DT_ID.eq(DT_MANIFEST.DT_ID))
                    .where(DT_MANIFEST.DT_MANIFEST_ID.eq(id));
            case DT_SC_MANIFEST -> dslContext().select(DT_SC.OWNER_USER_ID)
                    .from(DT_SC_MANIFEST)
                    .join(DT_SC).on(DT_SC.DT_SC_ID.eq(DT_SC_MANIFEST.DT_SC_ID))
                    .where(DT_SC_MANIFEST.DT_SC_MANIFEST_ID.eq(id));
            case CODE_LIST_MANIFEST -> dslContext().select(CODE_LIST.OWNER_USER_ID)
                    .from(CODE_LIST_MANIFEST)
                    .join(CODE_LIST).on(CODE_LIST.CODE_LIST_ID.eq(CODE_LIST_MANIFEST.CODE_LIST_ID))
                    .where(CODE_LIST_MANIFEST.CODE_LIST_MANIFEST_ID.eq(id));
            case CODE_LIST_VALUE_MANIFEST -> dslContext().select(CODE_LIST_VALUE.OWNER_USER_ID)
                    .from(CODE_LIST_VALUE_MANIFEST)
                    .join(CODE_LIST_VALUE).on(CODE_LIST_VALUE.CODE_LIST_VALUE_ID
                            .eq(CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_ID))
                    .where(CODE_LIST_VALUE_MANIFEST.CODE_LIST_VALUE_MANIFEST_ID.eq(id));
            case BIZ_CTX -> dslContext().select(BIZ_CTX.CREATED_BY)
                    .from(BIZ_CTX)
                    .where(BIZ_CTX.BIZ_CTX_ID.eq(id));
            case BIZ_CTX_VALUE -> dslContext().select(BIZ_CTX.CREATED_BY)
                    .from(BIZ_CTX_VALUE)
                    .join(BIZ_CTX).on(BIZ_CTX.BIZ_CTX_ID.eq(BIZ_CTX_VALUE.BIZ_CTX_ID))
                    .where(BIZ_CTX_VALUE.BIZ_CTX_VALUE_ID.eq(id));
            case CTX_CATEGORY -> dslContext().select(CTX_CATEGORY.CREATED_BY)
                    .from(CTX_CATEGORY)
                    .where(CTX_CATEGORY.CTX_CATEGORY_ID.eq(id));
            case CTX_SCHEME -> dslContext().select(CTX_SCHEME.CREATED_BY)
                    .from(CTX_SCHEME)
                    .where(CTX_SCHEME.CTX_SCHEME_ID.eq(id));
            case CTX_SCHEME_VALUE -> dslContext().select(CTX_SCHEME.CREATED_BY)
                    .from(CTX_SCHEME_VALUE)
                    .join(CTX_SCHEME).on(CTX_SCHEME.CTX_SCHEME_ID.eq(CTX_SCHEME_VALUE.OWNER_CTX_SCHEME_ID))
                    .where(CTX_SCHEME_VALUE.CTX_SCHEME_VALUE_ID.eq(id));
            case NAMESPACE -> dslContext().select(NAMESPACE.OWNER_USER_ID)
                    .from(NAMESPACE)
                    .where(NAMESPACE.NAMESPACE_ID.eq(id));
            case LIBRARY -> dslContext().select(LIBRARY.CREATED_BY)
                    .from(LIBRARY)
                    .where(LIBRARY.LIBRARY_ID.eq(id));
        };
    }

}

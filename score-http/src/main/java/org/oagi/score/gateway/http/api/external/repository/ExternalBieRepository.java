package org.oagi.score.gateway.http.api.external.repository;

import org.jooq.DSLContext;
import org.oagi.score.gateway.http.api.bie_management.model.TopLevelAsbiepId;
import org.oagi.score.gateway.http.api.bie_management.model.bie_package.BiePackageId;
import org.oagi.score.gateway.http.common.model.Guid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.*;

@Repository
public class ExternalBieRepository {

    @Autowired
    private DSLContext dslContext;

    public BiePackageId getBiePackageId(String libraryName, String biePackageName, String biePackageVersionId) {

        BiePackageId biePackageId = dslContext.select(
                BIE_PACKAGE.BIE_PACKAGE_ID)
                .from(BIE_PACKAGE)
                .join(LIBRARY).on(LIBRARY.LIBRARY_ID.eq(BIE_PACKAGE.LIBRARY_ID))
                .where(BIE_PACKAGE.NAME.eq(biePackageName).and(BIE_PACKAGE.VERSION_ID.eq(biePackageVersionId)))
                .and(LIBRARY.NAME.eq(libraryName))
                .fetchOneInto(BiePackageId.class);
        return biePackageId;
    }

    public TopLevelAsbiepId getTopLevelAsbiepId(Guid guid) {

        TopLevelAsbiepId topLevelAsbiepId = dslContext.select(
                ASBIEP.OWNER_TOP_LEVEL_ASBIEP_ID)
                .from(ASBIEP)
                .where(ASBIEP.GUID.eq(guid.toString()))
                .fetchOneInto(TopLevelAsbiepId.class);
        return topLevelAsbiepId;
    }

}

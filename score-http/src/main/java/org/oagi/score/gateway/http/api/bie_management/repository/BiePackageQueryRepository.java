package org.oagi.score.gateway.http.api.bie_management.repository;

import org.oagi.score.gateway.http.api.bie_management.model.BieListEntryRecord;
import org.oagi.score.gateway.http.api.bie_management.model.TopLevelAsbiepId;
import org.oagi.score.gateway.http.api.bie_management.model.bie_package.BiePackageDetailsRecord;
import org.oagi.score.gateway.http.api.bie_management.model.bie_package.BiePackageId;
import org.oagi.score.gateway.http.api.bie_management.model.bie_package.BiePackageListEntryRecord;
import org.oagi.score.gateway.http.api.bie_management.model.bie_package.BiePackageSummaryRecord;
import org.oagi.score.gateway.http.api.bie_management.repository.criteria.BieListInBiePackageFilterCriteria;
import org.oagi.score.gateway.http.api.bie_management.repository.criteria.BiePackageListFilterCriteria;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.ResultAndCount;

import java.util.Collection;
import java.util.List;

public interface BiePackageQueryRepository {

    BiePackageSummaryRecord getBiePackageSummary(BiePackageId biePackageId);

    List<BiePackageSummaryRecord> getBiePackageSummaryList(
            Collection<BiePackageId> biePackageIds);

    ResultAndCount<BiePackageListEntryRecord> getBiePackageList(
            BiePackageListFilterCriteria filterCriteria, PageRequest pageRequest);

    BiePackageDetailsRecord getBiePackageDetails(BiePackageId biePackageId);

    List<TopLevelAsbiepId> getTopLevelAsbiepIdListInBiePackage(BiePackageId biePackageId);

    ResultAndCount<BieListEntryRecord> getBieListInBiePackage(
            BieListInBiePackageFilterCriteria filterCriteria, PageRequest pageRequest);

    boolean exists(BiePackageId biePackageId, TopLevelAsbiepId topLevelAsbiepId);

    boolean hasDuplicateVersion(BiePackageId biePackageId, String versionId);
}

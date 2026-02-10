package org.oagi.score.gateway.http.api.bie_management.model.bie_package;

import org.oagi.score.gateway.http.api.account_management.model.UserSummaryRecord;
import org.oagi.score.gateway.http.api.bie_management.model.BieState;
import org.oagi.score.gateway.http.api.library_management.model.LibraryId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseSummaryRecord;
import org.oagi.score.gateway.http.common.model.AccessPrivilege;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.model.WhoAndWhen;

import java.util.List;

public record BiePackageListEntryRecord(
        BiePackageId biePackageId,
        Guid guid,
        LibraryId libraryId,
        String name,
        String versionId,
        String versionName,
        String description,
        List<ReleaseSummaryRecord> releases,
        BieState state,
        AccessPrivilege access,

        UserSummaryRecord owner,
        WhoAndWhen created,
        WhoAndWhen lastUpdated) {
}

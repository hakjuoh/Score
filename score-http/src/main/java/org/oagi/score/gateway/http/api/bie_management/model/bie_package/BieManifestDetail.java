package org.oagi.score.gateway.http.api.bie_management.model.bie_package;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.oagi.score.gateway.http.api.bie_management.model.TopLevelAsbiepSummaryRecord;
import org.oagi.score.gateway.http.common.model.Guid;

@JsonInclude
public record BieManifestDetail(Guid uuid,
                                String libraryName,
                                String branch,
                                String den,
                                String displayName,
                                String versionId) {

    public static BieManifestDetail newBieManifestDetail(TopLevelAsbiepSummaryRecord topLevelAsbiep) {
        return new BieManifestDetail(topLevelAsbiep.guid(),
                topLevelAsbiep.library().name(),
                topLevelAsbiep.release().releaseNum(),
                topLevelAsbiep.den(),
                topLevelAsbiep.displayName(),
                topLevelAsbiep.version());
    }

}

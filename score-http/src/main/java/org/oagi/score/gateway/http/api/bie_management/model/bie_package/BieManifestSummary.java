package org.oagi.score.gateway.http.api.bie_management.model.bie_package;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.oagi.score.gateway.http.api.bie_management.model.TopLevelAsbiepSummaryRecord;
import org.oagi.score.gateway.http.common.model.Guid;

@JsonInclude
public record BieManifestSummary(Guid uuid,
                                 String den,
                                 String displayName,
                                 String versionId) {

    public static BieManifestSummary newBieManifestSummary(TopLevelAsbiepSummaryRecord topLevelAsbiep) {
        return new BieManifestSummary(
                topLevelAsbiep.guid(),
                topLevelAsbiep.den(),
                topLevelAsbiep.displayName(),
                topLevelAsbiep.version()
        );
    }
}

package org.oagi.score.gateway.http.api.bie_management.model;

import org.oagi.score.gateway.http.api.bie_management.model.bie_package.BiePackageId;

import java.util.Date;

public record SourceBiePackageRecord(
        BiePackageId biePackageId,
        String versionName,
        String versionId,
        String sourceAction,
        Date when) {
}

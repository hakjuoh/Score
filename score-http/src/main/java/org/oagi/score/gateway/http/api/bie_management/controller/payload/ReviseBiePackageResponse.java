package org.oagi.score.gateway.http.api.bie_management.controller.payload;

import org.oagi.score.gateway.http.api.bie_management.model.bie_package.BiePackageId;

public record ReviseBiePackageResponse(BiePackageId biePackageId, BiePackageId prevBiePackageId) {
}

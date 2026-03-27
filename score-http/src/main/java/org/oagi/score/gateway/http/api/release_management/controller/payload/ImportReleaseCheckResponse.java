package org.oagi.score.gateway.http.api.release_management.controller.payload;

import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;

public record ImportReleaseCheckResponse(
        boolean blocked,
        boolean exists,
        ReleaseId releaseId,
        String libraryName,
        String releaseNum,
        String message) {
}

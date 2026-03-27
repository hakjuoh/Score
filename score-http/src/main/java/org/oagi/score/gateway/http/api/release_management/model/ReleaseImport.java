package org.oagi.score.gateway.http.api.release_management.model;

import java.util.List;
import java.util.Map;

public final class ReleaseImport {

    private ReleaseImport() {
    }

    public record Bundle(
            ReleaseExport.Metadata metadata,
            Map<String, List<Map<String, Object>>> payloadsByTable,
            boolean overwrite) {
    }

    public record Check(
            boolean blocked,
            boolean exists,
            ReleaseId existingReleaseId,
            String libraryName,
            String releaseNum,
            String message) {
    }
}

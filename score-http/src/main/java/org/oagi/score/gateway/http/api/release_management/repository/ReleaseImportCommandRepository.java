package org.oagi.score.gateway.http.api.release_management.repository;

import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseImport;

public interface ReleaseImportCommandRepository {

    ReleaseImport.Check checkImport(ReleaseImport.Bundle bundle);

    ReleaseId importRelease(ReleaseImport.Bundle bundle);
}

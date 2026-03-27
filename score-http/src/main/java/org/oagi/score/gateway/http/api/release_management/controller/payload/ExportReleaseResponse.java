package org.oagi.score.gateway.http.api.release_management.controller.payload;

import java.io.File;

public record ExportReleaseResponse(String filename, File file) {

}

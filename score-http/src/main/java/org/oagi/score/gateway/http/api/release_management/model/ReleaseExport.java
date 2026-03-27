package org.oagi.score.gateway.http.api.release_management.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;
import java.util.List;

public final class ReleaseExport {

    private ReleaseExport() {
    }

    public record Metadata(
            String format,
            @JsonProperty("format_version")
            String formatVersion,
            @JsonProperty("exported_at")
            String exportedAt,
            Source source,
            @JsonProperty("payload_files")
            List<PayloadFile> payloadFiles) {
    }

    public record Source(
            String product,
            LibraryKey library,
            ReleaseHeader release) {
    }

    public record PayloadFile(
            String table,
            List<String> files) {
    }

    public record LibraryKey(
            String name,
            String organization,
            String type,
            String domain) {
    }

    public record LibraryRow(
            @JsonProperty("_internal_id")
            BigInteger internalId,
            String name,
            String organization,
            String type,
            String domain,
            String description,
            String link,
            String state,
            boolean readOnly,
            boolean isDefault) {
    }

    public record NamespaceRow(
            @JsonProperty("_internal_id")
            BigInteger internalId,
            String uri,
            String prefix,
            String description,
            boolean isStandard) {
    }

    public record AppUserRow(
            @JsonProperty("_internal_id")
            BigInteger internalId,
            String loginId,
            String username,
            List<String> roles) {
    }

    public record ReleaseRef(
            String guid,
            @JsonProperty("release_num")
            String releaseNum) {
    }

    public record ReleaseHeader(
            String guid,
            @JsonProperty("release_num")
            String releaseNum,
            @JsonProperty("library_key")
            LibraryKey libraryKey,
            @JsonProperty("depends_on")
            ReleaseRef dependsOn) {
    }

    public record ReleaseRow(
            @JsonProperty("_internal_id")
            BigInteger internalId,
            String guid,
            String releaseNum,
            String state,
            String releaseNote,
            String releaseLicense,
            String namespaceUri,
            String createdByLoginId,
            String lastUpdatedByLoginId,
            String creationTimestamp,
            String lastUpdateTimestamp) {
    }
}

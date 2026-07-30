package org.oagi.score.gateway.http.api.ai_management.artifact.storage;

/** Provider-neutral byte storage; locations are opaque outside the selected provider. */
public interface AiArtifactStorage {

    String id();

    String store(String objectKey, String filename, String mediaType, byte[] content);

    byte[] load(String location);

    void delete(String location);
}

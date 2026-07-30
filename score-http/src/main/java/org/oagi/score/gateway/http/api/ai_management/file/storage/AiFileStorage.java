package org.oagi.score.gateway.http.api.ai_management.file.storage;

/** Provider-neutral byte storage; locations are opaque outside the selected provider. */
public interface AiFileStorage {

    String id();

    String store(String objectKey, String filename, String mediaType, byte[] content);

    byte[] load(String location);

    void delete(String location);
}

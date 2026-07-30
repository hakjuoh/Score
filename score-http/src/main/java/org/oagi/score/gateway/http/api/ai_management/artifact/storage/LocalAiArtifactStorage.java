package org.oagi.score.gateway.http.api.ai_management.artifact.storage;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public final class LocalAiArtifactStorage implements AiArtifactStorage {

    private final ScoreAiProperties properties;

    public LocalAiArtifactStorage(ScoreAiProperties properties) {
        this.properties = properties;
    }

    @Override public String id() { return "local"; }

    @Override
    public String store(String objectKey, String filename, String mediaType, byte[] content) {
        Path path = resolve(objectKey);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return objectKey;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not store the artifact on local storage.", failure);
        }
    }

    @Override
    public byte[] load(String location) {
        try {
            return Files.readAllBytes(resolve(location));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not load the artifact from local storage.", failure);
        }
    }

    @Override
    public void delete(String location) {
        try {
            Files.deleteIfExists(resolve(location));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not delete the artifact from local storage.", failure);
        }
    }

    private Path resolve(String objectKey) {
        if (!StringUtils.hasText(objectKey) || objectKey.startsWith("/")
                || objectKey.contains("..") || objectKey.contains("\\")) {
            throw new IllegalArgumentException("Invalid local artifact object key.");
        }
        String configured = properties.getTools().getArtifacts().getStorage().getLocal().getRootDirectory();
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException("Local artifact root-directory is required.");
        }
        Path root = Path.of(configured.strip()).toAbsolutePath().normalize();
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Artifact path escapes local storage.");
        return resolved;
    }
}

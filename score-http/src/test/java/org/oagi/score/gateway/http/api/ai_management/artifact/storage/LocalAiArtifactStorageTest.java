package org.oagi.score.gateway.http.api.ai_management.artifact.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAiArtifactStorageTest {

    @Test
    void storesLoadsAndDeletesOnlyInsideConfiguredRoot(@TempDir Path root) throws Exception {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getTools().getArtifacts().getStorage().getLocal().setRootDirectory(root.toString());
        LocalAiArtifactStorage storage = new LocalAiArtifactStorage(properties);
        byte[] content = "artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String location = storage.store("conversation/request/file.md", "file.md",
                "text/markdown", content);

        assertThat(location).isEqualTo("conversation/request/file.md");
        assertThat(storage.load(location)).isEqualTo(content);
        assertThat(Files.isRegularFile(root.resolve(location))).isTrue();
        storage.delete(location);
        assertThat(Files.exists(root.resolve(location))).isFalse();
        assertThatThrownBy(() -> storage.load("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

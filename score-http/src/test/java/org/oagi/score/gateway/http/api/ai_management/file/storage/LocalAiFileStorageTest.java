package org.oagi.score.gateway.http.api.ai_management.file.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAiFileStorageTest {

    @Test
    void storesLoadsAndDeletesOnlyInsideConfiguredRoot(@TempDir Path root) throws Exception {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getTools().getFiles().getStorage().getLocal().setRootDirectory(root.toString());
        LocalAiFileStorage storage = new LocalAiFileStorage(properties);
        byte[] content = "file".getBytes(java.nio.charset.StandardCharsets.UTF_8);

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

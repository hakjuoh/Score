package org.oagi.score.gateway.http.api.ai_management.tool.file;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.file.*;
import org.oagi.score.gateway.http.api.ai_management.tool.file.storage.AiFileStorage;
import org.oagi.score.gateway.http.api.ai_management.tool.file.storage.AiFileStorageRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class AiFileServiceTest {

    private final ScoreAiProperties properties = new ScoreAiProperties();
    private final AiFileRendererRegistry renderers = mock(AiFileRendererRegistry.class);
    private final AiFileStorageRegistry storage = mock(AiFileStorageRegistry.class);
    private final AiFileRepository repository = mock(AiFileRepository.class);
    private final AgentOutputGuardrailChain guardrails = mock(AgentOutputGuardrailChain.class);
    private final AiFileRequestDetector detector = mock(AiFileRequestDetector.class);
    private final ScoreUser owner = mock(ScoreUser.class);
    private AiFileService service;

    @BeforeEach
    void setUp() {
        service = new AiFileService(properties, renderers, storage, repository, guardrails, detector);
    }

    @Test
    void rejectsExpiredDownloadsBeforeReadingProviderContent() {
        AiFileRecord record = record("file-1", "location-1", "digest",
                Instant.now().minusSeconds(1));
        when(repository.findOwned(owner, "conversation-1", "file-1"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.download(owner, "conversation-1", "file-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");

        verify(storage, never()).require(any());
    }

    @Test
    void verifiesStoredBytesBeforeReturningADownload() {
        AiFileStorage provider = mock(AiFileStorage.class);
        AiFileRecord record = record("file-1", "location-1", "wrong-digest",
                Instant.now().plusSeconds(60));
        when(repository.findOwned(owner, "conversation-1", "file-1"))
                .thenReturn(Optional.of(record));
        when(storage.require("local")).thenReturn(provider);
        when(provider.load("location-1")).thenReturn("changed".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.download(owner, "conversation-1", "file-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void purgeContinuesAfterOneProviderFailureAndLeavesItsMetadataForRetry() {
        AiFileStorage provider = mock(AiFileStorage.class);
        AiFileRecord first = record("file-1", "location-1", "digest", Instant.EPOCH);
        AiFileRecord second = record("file-2", "location-2", "digest", Instant.EPOCH);
        when(repository.findExpired(any(), eq(100), any())).thenReturn(List.of(first, second));
        when(storage.require("local")).thenReturn(provider);
        doThrow(new IllegalStateException("unavailable")).when(provider).delete("location-1");

        assertThat(service.purgeExpired(Instant.now())).isEqualTo(1);

        verify(repository, never()).delete("file-1");
        verify(repository).delete("file-2");
    }

    @Test
    void purgeDrainsPastOneHundredFailuresWithoutStarvingLaterFiles() {
        AiFileStorage provider = mock(AiFileStorage.class);
        List<AiFileRecord> failed = IntStream.range(0, 100)
                .mapToObj(index -> record("failed-" + index, "failed-location-" + index,
                        "digest", Instant.EPOCH))
                .toList();
        AiFileRecord later = record("file-later", "location-later", "digest", Instant.EPOCH);
        when(repository.findExpired(any(), eq(100), any()))
                .thenReturn(failed, List.of(later));
        when(storage.require("local")).thenReturn(provider);
        doAnswer(invocation -> {
            String location = invocation.getArgument(0);
            if (location.startsWith("failed-location-")) {
                throw new IllegalStateException("unavailable");
            }
            return null;
        }).when(provider).delete(any());

        assertThat(service.purgeExpired(Instant.now())).isEqualTo(1);

        verify(repository).delete("file-later");
        verify(repository, never()).delete(org.mockito.ArgumentMatchers.startsWith("failed-"));
        verify(repository).findExpired(any(), eq(100), eq(
                failed.stream().map(AiFileRecord::fileId)
                        .collect(java.util.stream.Collectors.toSet())));
    }

    @Test
    void storageCleanupFailureDoesNotHideTheOriginalMetadataFailure() {
        byte[] content = "# Report".getBytes(StandardCharsets.UTF_8);
        AiFileRenderer renderer = mock(AiFileRenderer.class);
        AiFileStorage provider = mock(AiFileStorage.class);
        RuntimeException metadataFailure = new IllegalStateException("metadata failed");
        when(renderers.require("markdown")).thenReturn(renderer);
        when(renderer.render(any(), any())).thenReturn(
                new AiFileRenderer.RenderedFile(content, "text/markdown"));
        when(renderer.defaultExtension()).thenReturn("md");
        when(renderer.format()).thenReturn("markdown");
        when(guardrails.evaluate(any())).thenReturn(new AgentOutputGuardrailChain.Outcome(
                new AiMessage.Assistant("# Report"), null, List.of(), null));
        when(repository.findByRequest(any(), any(), any())).thenReturn(List.of());
        when(storage.selected()).thenReturn(provider);
        when(provider.store(any(), any(), any(), any())).thenReturn("location-1");
        when(repository.insert(eq(owner), any())).thenThrow(metadataFailure);
        doThrow(new IllegalStateException("cleanup failed")).when(provider).delete("location-1");
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "user-1", 0,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());

        assertThatThrownBy(() -> service.create(owner, scope, "markdown", "report.md",
                TextNode.valueOf("# Report"), Map.of()))
                .isSameAs(metadataFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement().extracting(Throwable::getMessage)
                        .isEqualTo("cleanup failed"));
    }

    private AiFileRecord record(String id, String location, String digest, Instant expiresAt) {
        return new AiFileRecord(id, "conversation-1", "request-1", "markdown", "report.md",
                "text/markdown", 8, digest, "local", location, Instant.EPOCH, expiresAt);
    }
}

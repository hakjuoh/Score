package org.oagi.score.gateway.http.api.ai_management.artifact;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.artifact.storage.AiArtifactStorage;
import org.oagi.score.gateway.http.api.ai_management.artifact.storage.AiArtifactStorageRegistry;
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

class AiArtifactServiceTest {

    private final ScoreAiProperties properties = new ScoreAiProperties();
    private final AiArtifactRendererRegistry renderers = mock(AiArtifactRendererRegistry.class);
    private final AiArtifactStorageRegistry storage = mock(AiArtifactStorageRegistry.class);
    private final AiArtifactRepository repository = mock(AiArtifactRepository.class);
    private final AgentOutputGuardrailChain guardrails = mock(AgentOutputGuardrailChain.class);
    private final AiArtifactRequestDetector detector = mock(AiArtifactRequestDetector.class);
    private final ScoreUser owner = mock(ScoreUser.class);
    private AiArtifactService service;

    @BeforeEach
    void setUp() {
        service = new AiArtifactService(properties, renderers, storage, repository, guardrails, detector);
    }

    @Test
    void rejectsExpiredDownloadsBeforeReadingProviderContent() {
        AiArtifactRecord record = record("artifact-1", "location-1", "digest",
                Instant.now().minusSeconds(1));
        when(repository.findOwned(owner, "conversation-1", "artifact-1"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.download(owner, "conversation-1", "artifact-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");

        verify(storage, never()).require(any());
    }

    @Test
    void verifiesStoredBytesBeforeReturningADownload() {
        AiArtifactStorage provider = mock(AiArtifactStorage.class);
        AiArtifactRecord record = record("artifact-1", "location-1", "wrong-digest",
                Instant.now().plusSeconds(60));
        when(repository.findOwned(owner, "conversation-1", "artifact-1"))
                .thenReturn(Optional.of(record));
        when(storage.require("local")).thenReturn(provider);
        when(provider.load("location-1")).thenReturn("changed".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.download(owner, "conversation-1", "artifact-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void purgeContinuesAfterOneProviderFailureAndLeavesItsMetadataForRetry() {
        AiArtifactStorage provider = mock(AiArtifactStorage.class);
        AiArtifactRecord first = record("artifact-1", "location-1", "digest", Instant.EPOCH);
        AiArtifactRecord second = record("artifact-2", "location-2", "digest", Instant.EPOCH);
        when(repository.findExpired(any(), eq(100), any())).thenReturn(List.of(first, second));
        when(storage.require("local")).thenReturn(provider);
        doThrow(new IllegalStateException("unavailable")).when(provider).delete("location-1");

        assertThat(service.purgeExpired(Instant.now())).isEqualTo(1);

        verify(repository, never()).delete("artifact-1");
        verify(repository).delete("artifact-2");
    }

    @Test
    void purgeDrainsPastOneHundredFailuresWithoutStarvingLaterArtifacts() {
        AiArtifactStorage provider = mock(AiArtifactStorage.class);
        List<AiArtifactRecord> failed = IntStream.range(0, 100)
                .mapToObj(index -> record("failed-" + index, "failed-location-" + index,
                        "digest", Instant.EPOCH))
                .toList();
        AiArtifactRecord later = record("artifact-later", "location-later", "digest", Instant.EPOCH);
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

        verify(repository).delete("artifact-later");
        verify(repository, never()).delete(org.mockito.ArgumentMatchers.startsWith("failed-"));
        verify(repository).findExpired(any(), eq(100), eq(
                failed.stream().map(AiArtifactRecord::artifactId)
                        .collect(java.util.stream.Collectors.toSet())));
    }

    @Test
    void storageCleanupFailureDoesNotHideTheOriginalMetadataFailure() {
        byte[] content = "# Report".getBytes(StandardCharsets.UTF_8);
        AiArtifactRenderer renderer = mock(AiArtifactRenderer.class);
        AiArtifactStorage provider = mock(AiArtifactStorage.class);
        RuntimeException metadataFailure = new IllegalStateException("metadata failed");
        when(renderers.require("markdown")).thenReturn(renderer);
        when(renderer.render(any(), any())).thenReturn(
                new AiArtifactRenderer.RenderedArtifact(content, "text/markdown"));
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

    private AiArtifactRecord record(String id, String location, String digest, Instant expiresAt) {
        return new AiArtifactRecord(id, "conversation-1", "request-1", "markdown", "report.md",
                "text/markdown", 8, digest, "local", location, Instant.EPOCH, expiresAt);
    }
}

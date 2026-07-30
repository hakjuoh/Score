package org.oagi.score.gateway.http.api.ai_management.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.artifact.storage.AiArtifactStorage;
import org.oagi.score.gateway.http.api.ai_management.artifact.storage.AiArtifactStorageRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@Service
public class AiArtifactService {

    private static final Logger LOG = LoggerFactory.getLogger(AiArtifactService.class);

    private final ScoreAiProperties properties;
    private final AiArtifactRendererRegistry renderers;
    private final AiArtifactStorageRegistry storage;
    private final AiArtifactRepository repository;
    private final AgentOutputGuardrailChain outputGuardrails;
    private final AiArtifactRequestDetector requestDetector;

    public AiArtifactService(ScoreAiProperties properties, AiArtifactRendererRegistry renderers,
                             AiArtifactStorageRegistry storage, AiArtifactRepository repository,
                             AgentOutputGuardrailChain outputGuardrails,
                             AiArtifactRequestDetector requestDetector) {
        this.properties = properties;
        this.renderers = renderers;
        this.storage = storage;
        this.repository = repository;
        this.outputGuardrails = outputGuardrails;
        this.requestDetector = requestDetector;
    }

    public AiArtifactDescriptor create(ScoreUser requester, ExecutionScope scope, String format,
                                       String requestedFilename, JsonNode content,
                                       Map<String, Object> options) {
        if (!properties.getTools().getArtifacts().isEnabled()) {
            throw new IllegalStateException("Assistant artifact generation is disabled.");
        }
        AiArtifactRenderer renderer = renderers.require(format);
        JsonNode safeContent = publicContent(content, scope);
        AiArtifactRenderer.RenderedArtifact rendered = renderer.render(safeContent,
                options != null ? Map.copyOf(options) : Map.of());
        byte[] bytes = rendered.content();
        long maximum = properties.getTools().getArtifacts().getMaxBytes().toBytes();
        if (bytes.length == 0 || bytes.length > maximum) {
            throw new IllegalArgumentException("Generated artifact size must be between 1 and "
                    + maximum + " bytes.");
        }
        String filename = safeFilename(requestedFilename, renderer);
        String digest = sha256(bytes);
        List<AiArtifactRecord> existing = repository.findByRequest(requester,
                scope.conversationId(), scope.requestId());
        AiArtifactRecord duplicate = existing.stream()
                .filter(value -> value.filename().equals(filename) && value.sha256().equals(digest))
                .findFirst().orElse(null);
        if (duplicate != null) return duplicate.descriptor();

        String artifactId = UUID.randomUUID().toString();
        String objectKey = scope.conversationId() + "/" + scope.requestId() + "/"
                + artifactId + "." + renderer.defaultExtension();
        AiArtifactStorage selectedStorage = storage.selected();
        String location = selectedStorage.store(objectKey, filename, rendered.mediaType(), bytes);
        Instant createdAt = Instant.now();
        AiArtifactRecord record = new AiArtifactRecord(artifactId, scope.conversationId(),
                scope.requestId(), renderer.format(), filename, rendered.mediaType(), bytes.length,
                digest, selectedStorage.id(), location, createdAt,
                createdAt.plus(properties.getTools().getArtifacts().getRetention()));
        try {
            AiArtifactRecord saved = repository.insert(requester, record);
            if (!saved.artifactId().equals(record.artifactId())) selectedStorage.delete(location);
            return saved.descriptor();
        } catch (RuntimeException failure) {
            try {
                selectedStorage.delete(location);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public List<AiArtifactDescriptor> findByRequest(ScoreUser requester, String conversationId,
                                                    String requestId) {
        return repository.findByRequest(requester, conversationId, requestId).stream()
                .map(AiArtifactRecord::descriptor).toList();
    }

    /** Ensures an explicit file request survives workflows whose final synthesizer has no tools. */
    public List<AiArtifactDescriptor> ensureRequestedArtifacts(ScoreUser requester,
                                                                ExecutionScope scope,
                                                                String prompt,
                                                                String safeAnswer) {
        List<AiArtifactDescriptor> existing = findByRequest(requester, scope.conversationId(), scope.requestId());
        if (!existing.isEmpty()) return existing;
        for (AiArtifactRequestDetector.Request request : requestDetector.detect(prompt)) {
            create(requester, scope, request.format(), request.filename(),
                    com.fasterxml.jackson.databind.node.TextNode.valueOf(safeAnswer), Map.of());
        }
        return findByRequest(requester, scope.conversationId(), scope.requestId());
    }

    public Download download(ScoreUser requester, String conversationId, String artifactId) {
        AiArtifactRecord record = repository.findOwned(requester, conversationId, artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact does not exist."));
        if (record.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("Artifact has expired.");
        byte[] content = storage.require(record.storageProvider()).load(record.storageLocation());
        if (!sha256(content).equals(record.sha256())) {
            throw new IllegalStateException("Stored artifact integrity check failed.");
        }
        return new Download(record.descriptor(), content);
    }

    public void deleteConversationArtifacts(ScoreUser requester, String conversationId) {
        for (AiArtifactRecord artifact : repository.findByConversation(requester, conversationId)) {
            delete(artifact);
        }
    }

    /** Deletes provider objects before scheduled retention cascades conversation metadata. */
    public void deleteConversationArtifactsForRetention(String conversationId) {
        for (AiArtifactRecord artifact : repository.findByConversationTreeForUpdate(conversationId)) {
            delete(artifact);
        }
    }

    public int purgeExpired(Instant now) {
        int deleted = 0;
        Set<String> failedArtifactIds = new HashSet<>();
        while (true) {
            List<AiArtifactRecord> batch = repository.findExpired(now, 100,
                    Set.copyOf(failedArtifactIds));
            if (batch.isEmpty()) break;
            for (AiArtifactRecord artifact : batch) {
                try {
                    delete(artifact);
                    deleted++;
                } catch (RuntimeException failure) {
                    failedArtifactIds.add(artifact.artifactId());
                    LOG.warn("Could not purge expired AI artifact {}; it will be retried: {}",
                            artifact.artifactId(), failure.toString());
                }
            }
            if (batch.size() < 100) break;
        }
        return deleted;
    }

    private void delete(AiArtifactRecord artifact) {
        storage.require(artifact.storageProvider()).delete(artifact.storageLocation());
        repository.delete(artifact.artifactId());
    }

    private JsonNode publicContent(JsonNode content, ExecutionScope scope) {
        if (content == null || !content.isTextual()) return content;
        AgentOutputGuardrailChain.Outcome outcome = outputGuardrails.evaluate(
                new AgentOutputGuardrail.Request(AgentOutputGuardrail.Scope.PUBLIC,
                        new AiMessage.Assistant(content.textValue()), scope,
                        Map.of("artifact", true)));
        if (!outcome.allowed()) {
            throw new IllegalArgumentException("Artifact content was rejected by the output policy.");
        }
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(outcome.output().content());
    }

    private String safeFilename(String requested, AiArtifactRenderer renderer) {
        String base = StringUtils.hasText(requested) ? requested.strip() : "assistant-report";
        base = base.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]+", "-")
                .replaceAll("\\s+", " ").replaceAll("^[. ]+|[. ]+$", "");
        if (base.isBlank()) base = "assistant-report";
        String extension = "." + renderer.defaultExtension().toLowerCase(Locale.ROOT);
        if (!base.toLowerCase(Locale.ROOT).endsWith(extension)) base += extension;
        if (base.length() > 240) {
            base = base.substring(0, 240 - extension.length()).stripTrailing() + extension;
        }
        return base;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    public record Download(AiArtifactDescriptor descriptor, byte[] content) {
        public Download { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
}

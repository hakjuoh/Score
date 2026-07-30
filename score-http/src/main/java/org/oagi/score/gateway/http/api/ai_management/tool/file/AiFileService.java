package org.oagi.score.gateway.http.api.ai_management.tool.file;

import com.fasterxml.jackson.databind.JsonNode;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.file.storage.AiFileStorage;
import org.oagi.score.gateway.http.api.ai_management.tool.file.storage.AiFileStorageRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class AiFileService {

    private static final Logger LOG = LoggerFactory.getLogger(AiFileService.class);

    private final ScoreAiProperties properties;
    private final AiFileRendererRegistry renderers;
    private final AiFileStorageRegistry storage;
    private final AiFileRepository repository;
    private final AgentOutputGuardrailChain outputGuardrails;
    private final AiFileRequestDetector requestDetector;

    public AiFileService(ScoreAiProperties properties, AiFileRendererRegistry renderers,
                             AiFileStorageRegistry storage, AiFileRepository repository,
                             AgentOutputGuardrailChain outputGuardrails,
                             AiFileRequestDetector requestDetector) {
        this.properties = properties;
        this.renderers = renderers;
        this.storage = storage;
        this.repository = repository;
        this.outputGuardrails = outputGuardrails;
        this.requestDetector = requestDetector;
    }

    public AiFileDescriptor create(ScoreUser requester, ExecutionScope scope, String format,
                                       String requestedFilename, JsonNode content,
                                       Map<String, Object> options) {
        if (!properties.getTools().getFiles().isEnabled()) {
            throw new IllegalStateException("Assistant file generation is disabled.");
        }
        AiFileRenderer renderer = renderers.require(format);
        JsonNode safeContent = publicContent(content, scope);
        AiFileRenderer.RenderedFile rendered = renderer.render(safeContent,
                options != null ? Map.copyOf(options) : Map.of());
        byte[] bytes = rendered.content();
        long maximum = properties.getTools().getFiles().getMaxBytes().toBytes();
        if (bytes.length == 0 || bytes.length > maximum) {
            throw new IllegalArgumentException("Generated file size must be between 1 and "
                    + maximum + " bytes.");
        }
        String filename = safeFilename(requestedFilename, renderer);
        String digest = sha256(bytes);
        List<AiFileRecord> existing = repository.findByRequest(requester,
                scope.conversationId(), scope.requestId());
        AiFileRecord duplicate = existing.stream()
                .filter(value -> value.filename().equals(filename) && value.sha256().equals(digest))
                .findFirst().orElse(null);
        if (duplicate != null) return duplicate.descriptor();

        String fileId = UUID.randomUUID().toString();
        String objectKey = scope.conversationId() + "/" + scope.requestId() + "/"
                + fileId + "." + renderer.defaultExtension();
        AiFileStorage selectedStorage = storage.selected();
        String location = selectedStorage.store(objectKey, filename, rendered.mediaType(), bytes);
        Instant createdAt = Instant.now();
        AiFileRecord record = new AiFileRecord(fileId, scope.conversationId(),
                scope.requestId(), renderer.format(), filename, rendered.mediaType(), bytes.length,
                digest, selectedStorage.id(), location, createdAt,
                createdAt.plus(properties.getTools().getFiles().getRetention()));
        try {
            AiFileRecord saved = repository.insert(requester, record);
            if (!saved.fileId().equals(record.fileId())) selectedStorage.delete(location);
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

    public List<AiFileDescriptor> findByRequest(ScoreUser requester, String conversationId,
                                                    String requestId) {
        return repository.findByRequest(requester, conversationId, requestId).stream()
                .map(AiFileRecord::descriptor).toList();
    }

    /** Ensures an explicit file request survives workflows whose final synthesizer has no tools. */
    public List<AiFileDescriptor> ensureRequestedFiles(ScoreUser requester,
                                                                ExecutionScope scope,
                                                                String prompt,
                                                                String safeAnswer) {
        List<AiFileDescriptor> existing = findByRequest(requester, scope.conversationId(), scope.requestId());
        if (!existing.isEmpty()) return existing;
        for (AiFileRequestDetector.Request request : requestDetector.detect(prompt)) {
            create(requester, scope, request.format(), request.filename(),
                    com.fasterxml.jackson.databind.node.TextNode.valueOf(safeAnswer), Map.of());
        }
        return findByRequest(requester, scope.conversationId(), scope.requestId());
    }

    public Download download(ScoreUser requester, String conversationId, String fileId) {
        AiFileRecord record = repository.findOwned(requester, conversationId, fileId)
                .orElseThrow(() -> new IllegalArgumentException("File does not exist."));
        if (record.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("File has expired.");
        byte[] content = storage.require(record.storageProvider()).load(record.storageLocation());
        if (!sha256(content).equals(record.sha256())) {
            throw new IllegalStateException("Stored file integrity check failed.");
        }
        return new Download(record.descriptor(), content);
    }

    public void deleteConversationFiles(ScoreUser requester, String conversationId) {
        for (AiFileRecord file : repository.findByConversation(requester, conversationId)) {
            delete(file);
        }
    }

    /** Deletes provider objects before scheduled retention cascades conversation metadata. */
    public void deleteConversationFilesForRetention(String conversationId) {
        for (AiFileRecord file : repository.findByConversationTreeForUpdate(conversationId)) {
            delete(file);
        }
    }

    public int purgeExpired(Instant now) {
        int deleted = 0;
        Set<String> failedFileIds = new HashSet<>();
        while (true) {
            List<AiFileRecord> batch = repository.findExpired(now, 100,
                    Set.copyOf(failedFileIds));
            if (batch.isEmpty()) break;
            for (AiFileRecord file : batch) {
                try {
                    delete(file);
                    deleted++;
                } catch (RuntimeException failure) {
                    failedFileIds.add(file.fileId());
                    LOG.warn("Could not purge expired AI file {}; it will be retried: {}",
                            file.fileId(), failure.toString());
                }
            }
            if (batch.size() < 100) break;
        }
        return deleted;
    }

    private void delete(AiFileRecord file) {
        storage.require(file.storageProvider()).delete(file.storageLocation());
        repository.delete(file.fileId());
    }

    private JsonNode publicContent(JsonNode content, ExecutionScope scope) {
        if (content == null || !content.isTextual()) return content;
        AgentOutputGuardrailChain.Outcome outcome = outputGuardrails.evaluate(
                new AgentOutputGuardrail.Request(AgentOutputGuardrail.Scope.PUBLIC,
                        new AiMessage.Assistant(content.textValue()), scope,
                        Map.of("file", true)));
        if (!outcome.allowed()) {
            throw new IllegalArgumentException("File content was rejected by the output policy.");
        }
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(outcome.output().content());
    }

    private String safeFilename(String requested, AiFileRenderer renderer) {
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

    public record Download(AiFileDescriptor descriptor, byte[] content) {
        public Download { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
}

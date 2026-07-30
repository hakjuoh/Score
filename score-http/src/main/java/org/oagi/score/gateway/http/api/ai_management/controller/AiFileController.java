package org.oagi.score.gateway.http.api.ai_management.controller;

import org.oagi.score.gateway.http.api.ai_management.file.AiFileService;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/ai/chat/conversations/{conversationId}/files")
public class AiFileController {

    private final AiFileService files;
    private final SessionService sessions;

    public AiFileController(AiFileService files, SessionService sessions) {
        this.files = files;
        this.sessions = sessions;
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<ByteArrayResource> download(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String fileId) {
        AiFileService.Download download = files.download(
                sessions.asScoreUser(principal), conversationId, fileId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.descriptor().mediaType());
        } catch (IllegalArgumentException malformed) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(download.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.descriptor().filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(download.content()));
    }
}

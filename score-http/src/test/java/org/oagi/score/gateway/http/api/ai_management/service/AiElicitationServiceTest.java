package org.oagi.score.gateway.http.api.ai_management.service;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationNotice;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiElicitationServiceTest {

    private final ScoreUser user = user(1L, "tester");

    @Test
    void resumesTheSameMcpElicitationWithStructuredUserContent() throws Exception {
        AiElicitationService service = service();
        ArrayBlockingQueue<AiElicitationNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<McpSchema.ElicitResult> result = CompletableFuture.supplyAsync(() ->
                service.await(user, "conversation-1", "request-1", request(), notices::add));

        AiElicitationNotice notice = notices.poll(1, TimeUnit.SECONDS);
        assertThat(notice).isNotNull();
        assertThat(notice.requestedSchema()).containsEntry("type", "object");

        service.decide(user, "request-1", "conversation-1", notice.elicitationId(),
                "ACCEPT", Map.of("definition", "Reusable invoice data"));

        assertThat(result.get(1, TimeUnit.SECONDS).action())
                .isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
        assertThat(result.get().content())
                .containsEntry("definition", "Reusable invoice data");
    }

    @Test
    void rejectsAResponseFromAnotherUserWithoutReleasingThePendingRequest() throws Exception {
        AiElicitationService service = service();
        ArrayBlockingQueue<AiElicitationNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<McpSchema.ElicitResult> result = CompletableFuture.supplyAsync(() ->
                service.await(user, "conversation-1", "request-1", request(), notices::add));
        AiElicitationNotice notice = notices.poll(1, TimeUnit.SECONDS);

        assertThatThrownBy(() -> service.decide(user(2L, "other"), "request-1",
                "conversation-1", notice.elicitationId(), "ACCEPT", Map.of()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(result).isNotDone();

        service.cancelRequest("request-1");
        assertThat(result.get(1, TimeUnit.SECONDS).action())
                .isEqualTo(McpSchema.ElicitResult.Action.CANCEL);
    }

    @Test
    void elicitationWaitOutlivesRequestInactivityAndUsesItsOwnTimeout() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-long-interaction", "conversation-1", user,
                java.time.Instant.now().plusMillis(80));
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setElicitationTimeout(Duration.ofSeconds(2));
        AiElicitationService service = new AiElicitationService(properties, registry);
        ArrayBlockingQueue<AiElicitationNotice> notices = new ArrayBlockingQueue<>(1);
        CompletableFuture<McpSchema.ElicitResult> result = CompletableFuture.supplyAsync(() -> {
            assertThat(registry.start(entry)).isTrue();
            McpSchema.ElicitResult elicited = service.await(user, "conversation-1",
                    entry.requestId(), request(), notices::add);
            registry.finish(entry, null);
            return elicited;
        });

        AiElicitationNotice notice = notices.poll(1, TimeUnit.SECONDS);
        Thread.sleep(240);

        assertThat(result).isNotDone();
        assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("RUNNING");

        service.decide(user, entry.requestId(), "conversation-1", notice.elicitationId(),
                "ACCEPT", Map.of("definition", "Still active"));
        assertThat(result.get(1, TimeUnit.SECONDS).action())
                .isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
        assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("COMPLETED");
    }

    @Test
    void failedNoticePublicationReleasesTheInteractionProtection() throws Exception {
        AiRequestRegistry registry = new AiRequestRegistry();
        AiRequestRegistry.Entry entry = registry.register(
                "request-failed-notice", "conversation-1", user,
                java.time.Instant.now().plusMillis(80));
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setElicitationTimeout(Duration.ofSeconds(2));
        AiElicitationService service = new AiElicitationService(properties, registry);

        assertThatThrownBy(() -> service.await(user, "conversation-1", entry.requestId(),
                request(), ignored -> {
                    throw new IllegalStateException("socket unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessage("socket unavailable");

        Thread.sleep(200);
        assertThat(registry.status(entry.requestId(), user).status()).isEqualTo("TIMED_OUT");
    }

    private AiElicitationService service() {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setElicitationTimeout(Duration.ofSeconds(2));
        return new AiElicitationService(properties);
    }

    private McpSchema.ElicitFormRequest request() {
        return new McpSchema.ElicitFormRequest("Enter a definition.", Map.of(
                "type", "object",
                "properties", Map.of("definition", Map.of(
                        "type", "string", "minLength", 1)),
                "required", List.of("definition")), null);
    }

    private ScoreUser user(long id, String username) {
        return new ScoreUser(new UserId(BigInteger.valueOf(id)), username, username,
                null, false, List.of());
    }
}

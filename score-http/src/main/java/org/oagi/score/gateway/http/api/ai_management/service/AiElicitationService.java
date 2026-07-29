package org.oagi.score.gateway.http.api.ai_management.service;

import io.modelcontextprotocol.spec.McpSchema;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiElicitationPending;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Bridges synchronous MCP form elicitation to an interactive chat-panel response. */
@Component
public class AiElicitationService {

    private static final int MAX_PENDING = 10_000;

    private final Map<String, AiElicitationPending> pending = new ConcurrentHashMap<>();
    private final Duration elicitationTimeout;
    private final AiRequestRegistry requests;

    @Autowired
    public AiElicitationService(ScoreAiProperties properties, AiRequestRegistry requests) {
        this.elicitationTimeout = properties.getElicitationTimeout();
        this.requests = requests;
    }

    public AiElicitationService(ScoreAiProperties properties) {
        this(properties, null);
    }

    public McpSchema.ElicitResult await(ScoreUser requester, String conversationId, String requestId,
                                       McpSchema.ElicitFormRequest request,
                                       Consumer<AiElicitationNotice> noticeConsumer) {
        if (request == null || !StringUtils.hasText(request.message())
                || request.requestedSchema() == null) {
            return cancelled();
        }
        if (pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("Too many AI user interactions are pending.");
        }
        String elicitationId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(elicitationTimeout);
        AiElicitationPending interaction = new AiElicitationPending(
                elicitationId, requester.userId().value().toString(), conversationId, requestId,
                new CompletableFuture<>(), expiresAt);
        if (pending.putIfAbsent(elicitationId, interaction) != null) {
            throw new IllegalStateException("Could not reserve an AI user interaction.");
        }
        boolean protectedFromInactivity = requests == null
                || requests.interactionStarted(requestId);
        if (!protectedFromInactivity) {
            pending.remove(elicitationId, interaction);
            return cancelled();
        }
        try {
            noticeConsumer.accept(new AiElicitationNotice(elicitationId, requestId, conversationId,
                    request.message(), request.requestedSchema(), expiresAt));
            return interaction.response().get(
                    Math.max(1L, elicitationTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return cancelled();
        } catch (TimeoutException exception) {
            return cancelled();
        } catch (ExecutionException exception) {
            throw new IllegalStateException("The AI user interaction could not be completed.",
                    exception.getCause());
        } finally {
            pending.remove(elicitationId, interaction);
            if (requests != null) {
                requests.interactionFinished(requestId);
            }
        }
    }

    public void decide(ScoreUser requester, String requestId, String conversationId,
                       String elicitationId, String action, Map<String, Object> content) {
        AiElicitationPending interaction = pending.get(elicitationId);
        if (interaction == null) {
            throw new IllegalArgumentException("The AI user interaction is no longer pending.");
        }
        if (!interaction.appUserId().equals(requester.userId().value().toString())) {
            throw new AccessDeniedException("The AI user interaction belongs to another user.");
        }
        if (!interaction.requestId().equals(requestId)
                || !interaction.conversationId().equals(conversationId)) {
            throw new IllegalArgumentException("The AI user interaction identity does not match.");
        }
        if (Instant.now().isAfter(interaction.expiresAt())) {
            pending.remove(elicitationId, interaction);
            interaction.response().complete(cancelled());
            throw new IllegalArgumentException("The AI user interaction expired.");
        }
        McpSchema.ElicitResult.Action resultAction = switch (
                StringUtils.hasText(action) ? action.strip().toUpperCase() : "") {
            case "ACCEPT" -> McpSchema.ElicitResult.Action.ACCEPT;
            case "DECLINE" -> McpSchema.ElicitResult.Action.DECLINE;
            case "CANCEL" -> McpSchema.ElicitResult.Action.CANCEL;
            default -> throw new IllegalArgumentException("Unsupported AI user interaction action.");
        };
        Map<String, Object> resultContent = resultAction == McpSchema.ElicitResult.Action.ACCEPT
                ? immutableMap(content) : null;
        if (!interaction.response().complete(
                new McpSchema.ElicitResult(resultAction, resultContent))) {
            throw new IllegalArgumentException("The AI user interaction was already answered.");
        }
    }

    public void cancelRequest(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return;
        }
        pending.values().stream()
                .filter(interaction -> requestId.equals(interaction.requestId()))
                .forEach(interaction -> interaction.response().complete(cancelled()));
    }

    private McpSchema.ElicitResult cancelled() {
        return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, null);
    }

    private Map<String, Object> immutableMap(Map<String, Object> source) {
        return source != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(source)) : Map.of();
    }

}

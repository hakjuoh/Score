package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiMutationConfirmationDecisionResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.MutationConfirmation;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationAuthorization;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationState;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationDecision;
import org.oagi.score.gateway.http.api.ai_management.model.CreateAiMutationConfirmationArguments;
import org.oagi.score.gateway.http.api.ai_management.repository.AiMutationConfirmationCommandRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiMutationConfirmationQueryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Coordinates server-authoritative, one-time approval for mutation tool invocations.
 * Persistence is delegated to query and command repositories while this service owns
 * argument canonicalization, grant generation, redaction, and lifecycle policy.
 */
@Service
public class AiMutationConfirmationService {

    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);
    private static final int MAX_ARGUMENT_SUMMARY_CHARS = 2000;
    private static final int MAX_REVISION_PROMPT_CHARS = 32_768;

    private final Function<ScoreUser, AiMutationConfirmationQueryRepository> queryRepositories;
    private final Function<ScoreUser, AiMutationConfirmationCommandRepository> commandRepositories;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates the mutation-confirmation coordinator.
     *
     * @param repositoryFactory factory that binds repositories to the signed-in requester
     * @param objectMapper mapper used to canonicalize tool arguments
     */
    @Autowired
    public AiMutationConfirmationService(
            RepositoryFactory repositoryFactory,
            ObjectMapper objectMapper) {
        this(repositoryFactory::aiMutationConfirmationQueryRepository,
                repositoryFactory::aiMutationConfirmationCommandRepository,
                objectMapper);
    }

    AiMutationConfirmationService(
            AiMutationConfirmationQueryRepository queryRepository,
            AiMutationConfirmationCommandRepository commandRepository,
            ObjectMapper objectMapper) {
        this(ignored -> queryRepository, ignored -> commandRepository, objectMapper);
    }

    AiMutationConfirmationService(
            Function<ScoreUser, AiMutationConfirmationQueryRepository> queryRepositories,
            Function<ScoreUser, AiMutationConfirmationCommandRepository> commandRepositories,
            ObjectMapper objectMapper) {
        this.queryRepositories = queryRepositories;
        this.commandRepositories = commandRepositories;
        this.objectMapper = objectMapper;
    }

    /**
     * Authorizes a mutation by consuming a valid supplied grant or issuing an approval request.
     */
    @Transactional
    public AiMutationAuthorization authorize(
            ScoreUser requester, String conversationId, String requestId,
            MutationConfirmation supplied, String toolName, String input) {
        AiMutationConfirmationQueryRepository queryRepository = queryRepository(requester);
        AiMutationConfirmationCommandRepository commandRepository = commandRepository(requester);
        String actualArgumentsDigest = argumentsDigest(toolName, input);
        String suppliedDigest = supplied != null && supplied.revised()
                ? revisionDigest(toolName, supplied.revisionPrompt())
                : actualArgumentsDigest;
        if (supplied != null && StringUtils.hasText(supplied.confirmationRequestId())
                && StringUtils.hasText(supplied.confirmationGrant())
                && (!supplied.revised() || Objects.equals(supplied.toolName(), toolName))
                && consume(queryRepository, commandRepository, conversationId,
                supplied, toolName, suppliedDigest)) {
            return AiMutationAuthorization.permitted();
        }
        return AiMutationAuthorization.required(issue(queryRepository, commandRepository,
                conversationId, requestId, toolName, actualArgumentsDigest, input));
    }

    /**
     * Applies an approval or denial decision without a revision prompt.
     */
    @Transactional
    public AiMutationDecision decide(
            ScoreUser requester, String conversationId, String confirmationRequestId,
            String requestedDecision) {
        return decide(requester, conversationId, confirmationRequestId, requestedDecision, null);
    }

    /**
     * Applies an approval or denial decision and optionally binds approval to a revised prompt.
     */
    @Transactional
    public AiMutationDecision decide(
            ScoreUser requester, String conversationId, String confirmationRequestId,
            String requestedDecision, String revisionPrompt) {
        AiMutationConfirmationQueryRepository queryRepository = queryRepository(requester);
        AiMutationConfirmationCommandRepository commandRepository = commandRepository(requester);
        String decision = StringUtils.hasText(requestedDecision)
                ? requestedDecision.strip().toUpperCase() : "";
        if (!"APPROVE".equals(decision) && !"DENY".equals(decision)) {
            throw new IllegalArgumentException("Mutation confirmation decision must be APPROVE or DENY.");
        }
        AiMutationConfirmationState row = ownedForUpdate(
                queryRepository, conversationId, confirmationRequestId);
        Instant now = Instant.now();
        if (("REQUESTED".equals(row.status()) || "APPROVED".equals(row.status()))
                && !row.expiresAt().isAfter(now)) {
            commandRepository.markExpired(row.id(), now);
            row = row.withExpired(now);
        }
        if ("CONSUMED".equals(row.status())) {
            return new AiMutationDecision(response(row, "CONSUMED", null), HttpStatus.CONFLICT);
        }
        if ("EXPIRED".equals(row.status())) {
            return new AiMutationDecision(response(row, "EXPIRED", null), HttpStatus.CONFLICT);
        }
        if ("APPROVE".equals(decision)) {
            if ("APPROVED".equals(row.status())) {
                return new AiMutationDecision(response(row, "ALREADY_APPROVED", null), HttpStatus.OK);
            }
            if ("DENIED".equals(row.status())) {
                return new AiMutationDecision(response(row, "ALREADY_DENIED", null), HttpStatus.CONFLICT);
            }
            String revisedDigest = null;
            if (StringUtils.hasText(revisionPrompt)) {
                String normalizedRevision = revisionPrompt.strip();
                if (normalizedRevision.length() > MAX_REVISION_PROMPT_CHARS) {
                    throw new IllegalArgumentException("Mutation revision prompt is too long.");
                }
                revisedDigest = revisionDigest(row.toolName(), normalizedRevision);
            }
            String grant = grant();
            commandRepository.approve(row.id(), sha256(grant), now,
                    revisedDigest != null ? revisedDigest : row.argumentsDigest());
            row = row.withApproved(now,
                    revisedDigest != null ? revisedDigest : row.argumentsDigest());
            return new AiMutationDecision(response(row, "APPROVED", grant), HttpStatus.OK);
        }
        if ("DENIED".equals(row.status())) {
            return new AiMutationDecision(response(row, "ALREADY_DENIED", null), HttpStatus.OK);
        }
        commandRepository.deny(row.id(), now);
        row = row.withDenied(now);
        return new AiMutationDecision(response(row, "DENIED", null), HttpStatus.OK);
    }

    private boolean consume(
            AiMutationConfirmationQueryRepository queryRepository,
            AiMutationConfirmationCommandRepository commandRepository,
            String conversationId, MutationConfirmation supplied,
            String toolName, String argumentsDigest) {
        /*
         * request_id identifies the original blocked turn. The approved follow-up
         * intentionally has a fresh request ID, so redemption is bound instead to
         * owner + conversation + tool + canonical arguments + one-time grant.
         */
        AiMutationConfirmationState row = queryRepository.findOwnedForUpdate(
                        conversationId, supplied.confirmationRequestId())
                .orElse(null);
        if (row == null) {
            return false;
        }
        Instant now = Instant.now();
        if (!"APPROVED".equals(row.status()) || !row.expiresAt().isAfter(now)
                || !Objects.equals(row.toolName(), toolName)
                || !Objects.equals(row.argumentsDigest(), argumentsDigest)
                || !constantTimeEquals(row.grantDigest(), sha256(supplied.confirmationGrant()))) {
            if ("APPROVED".equals(row.status()) && !row.expiresAt().isAfter(now)) {
                commandRepository.markExpired(row.id(), now);
            }
            return false;
        }
        return commandRepository.consume(row.id(), now);
    }

    private AiMutationConfirmationNotice issue(
            AiMutationConfirmationQueryRepository queryRepository,
            AiMutationConfirmationCommandRepository commandRepository,
            String conversationId, String requestId,
            String toolName, String argumentsDigest, String input) {
        Instant now = Instant.now();
        AiMutationConfirmationState existing = queryRepository.findReusableForUpdate(
                        conversationId, requestId, toolName, argumentsDigest, now)
                .orElse(null);
        if (existing != null) {
            return new AiMutationConfirmationNotice(
                    existing.guid(), existing.status(), existing.expiresAt(), toolName,
                    argumentSummary(input));
        }
        String guid = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(CONFIRMATION_TTL);
        boolean inserted = commandRepository.create(conversationId,
                new CreateAiMutationConfirmationArguments(
                        guid, requestId, toolName, argumentsDigest, expiresAt, now));
        if (!inserted) {
            throw new AccessDeniedException("AI conversation does not exist or is not owned by the signed-in user.");
        }
        return new AiMutationConfirmationNotice(
                guid, "REQUESTED", expiresAt, toolName, argumentSummary(input));
    }

    private AiMutationConfirmationState ownedForUpdate(
            AiMutationConfirmationQueryRepository queryRepository,
            String conversationId, String confirmationRequestId) {
        return queryRepository.findOwnedForUpdate(conversationId, confirmationRequestId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Mutation confirmation does not exist or is not owned by the signed-in user."));
    }

    private AiMutationConfirmationQueryRepository queryRepository(ScoreUser requester) {
        ScoreUser requiredRequester = Objects.requireNonNull(requester, "requester");
        return Objects.requireNonNull(
                queryRepositories.apply(requiredRequester), "queryRepository");
    }

    private AiMutationConfirmationCommandRepository commandRepository(ScoreUser requester) {
        ScoreUser requiredRequester = Objects.requireNonNull(requester, "requester");
        return Objects.requireNonNull(
                commandRepositories.apply(requiredRequester), "commandRepository");
    }

    private AiMutationConfirmationDecisionResponse response(
            AiMutationConfirmationState row, String disposition, String grant) {
        return new AiMutationConfirmationDecisionResponse(row.guid(), null, row.status(), disposition,
                row.expiresAt(), row.approvedAt(), row.deniedAt(), row.expiredAt(), row.consumedAt(), grant);
    }

    /**
     * Adds the public conversation identifier to a stable decision response.
     *
     * @param response decision response produced by this service
     * @param conversationId public conversation identifier
     * @return response bound to the conversation
     */
    public AiMutationConfirmationDecisionResponse bindConversation(
            AiMutationConfirmationDecisionResponse response, String conversationId) {
        return new AiMutationConfirmationDecisionResponse(response.confirmationRequestId(), conversationId,
                response.status(), response.disposition(), response.expiresAt(), response.approvedAt(),
                response.deniedAt(), response.expiredAt(), response.consumedAt(), response.confirmationGrant());
    }

    String argumentsDigest(String toolName, String input) {
        Object parsed;
        try {
            parsed = StringUtils.hasText(input) ? objectMapper.readValue(input, Object.class) : Map.of();
        } catch (JsonProcessingException exception) {
            parsed = Objects.requireNonNullElse(input, "");
        }
        try {
            return sha256(toolName + "\n" + objectMapper.writeValueAsString(canonical(parsed)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not canonicalize mutation tool arguments.", exception);
        }
    }

    String revisionDigest(String toolName, String revisionPrompt) {
        if (!StringUtils.hasText(toolName) || !StringUtils.hasText(revisionPrompt)) {
            return "";
        }
        return sha256("revised\n" + toolName + "\n" + revisionPrompt.strip());
    }

    String argumentSummary(String input) {
        Object parsed;
        try {
            parsed = StringUtils.hasText(input) ? objectMapper.readValue(input, Object.class) : Map.of();
        } catch (JsonProcessingException exception) {
            return "[Invalid non-JSON tool arguments]";
        }
        try {
            String summary = objectMapper.writeValueAsString(redact(canonical(parsed)));
            return summary.length() <= MAX_ARGUMENT_SUMMARY_CHARS ? summary
                    : summary.substring(0, MAX_ARGUMENT_SUMMARY_CHARS) + "...[truncated]";
        } catch (JsonProcessingException exception) {
            return "[Tool arguments could not be displayed]";
        }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new TreeMap<>();
            map.forEach((key, item) -> {
                String name = Objects.toString(key);
                redacted.put(name, AiSensitiveDataRedactor.isSensitiveKey(name)
                        ? "[REDACTED]" : redact(item));
            });
            return redacted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redact).toList();
        }
        return value instanceof String text ? AiSensitiveDataRedactor.redactText(text) : value;
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(Objects.toString(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private String grant() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

}

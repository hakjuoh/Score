package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeRule;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChangeOwnershipQueryRepository;
import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeOwnershipPolicy;
import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeRiskCatalog;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides whether the requester owns the record a data-changing tool call would change.
 *
 * <p>The check is fail-closed: an unclassified tool, a missing or unreadable identifier, an
 * absent record, a record without a recorded owner, and a failed lookup all report "not owned",
 * which sends the call to explicit approval rather than running it unapproved.
 */
@Service
public class AiChangeOwnershipService implements AiChangeOwnershipPolicy {

    private static final Logger logger = LoggerFactory.getLogger(AiChangeOwnershipService.class);

    private final Function<ScoreUser, AiChangeOwnershipQueryRepository> queryRepositories;
    private final ObjectMapper objectMapper;

    /**
     * Creates the ownership check bound to the signed-in requester.
     *
     * @param repositoryFactory factory that binds repositories to the signed-in requester
     * @param objectMapper mapper used to read canonical tool arguments
     */
    @Autowired
    public AiChangeOwnershipService(
            RepositoryFactory repositoryFactory, ObjectMapper objectMapper) {
        this(repositoryFactory::aiChangeOwnershipQueryRepository, objectMapper);
    }

    AiChangeOwnershipService(
            Function<ScoreUser, AiChangeOwnershipQueryRepository> queryRepositories,
            ObjectMapper objectMapper) {
        this.queryRepositories = queryRepositories;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean requesterOwnsTarget(ScoreUser requester, String toolName, String arguments) {
        AiChangeRule rule = AiChangeRiskCatalog.ruleOf(toolName);
        if (requester == null || requester.userId() == null || rule.targetKind() == null) {
            return false;
        }
        Optional<BigInteger> targetId = targetId(rule.targetParameter(), arguments);
        if (targetId.isEmpty()) {
            return false;
        }
        try {
            return queryRepository(requester)
                    .findOwner(rule.targetKind(), targetId.get())
                    .filter(owner -> Objects.equals(owner, requester.userId()))
                    .isPresent();
        } catch (RuntimeException exception) {
            logger.warn("Could not resolve the owner of the {} target of {}; approval is required.",
                    rule.targetKind(), toolName, exception);
            return false;
        }
    }

    private Optional<BigInteger> targetId(String parameter, String arguments) {
        JsonNode node;
        try {
            node = objectMapper.readTree(Objects.requireNonNullElse(arguments, ""));
        } catch (com.fasterxml.jackson.core.JacksonException exception) {
            return Optional.empty();
        }
        JsonNode value = node != null && node.isObject() ? node.get(parameter) : null;
        if (value == null) {
            return Optional.empty();
        }
        if (value.isIntegralNumber()) {
            return Optional.of(value.bigIntegerValue());
        }
        if (value.isTextual()) {
            try {
                return Optional.of(new BigInteger(value.textValue().strip()));
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private AiChangeOwnershipQueryRepository queryRepository(ScoreUser requester) {
        return Objects.requireNonNull(
                queryRepositories.apply(requester), "queryRepository");
    }

}

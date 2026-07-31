package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderView;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyErrorCode;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyViolationException;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.net.URI;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CATALOG_AUDIT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

/** Transactional provider catalog management with write-only encrypted credentials. */
@Service
public class AiProviderCatalogService {

    private final DSLContext dsl;
    private final ApplicationSecretService secrets;
    private final AiAdminPolicyService authorization;
    private final ObjectMapper objectMapper;

    public AiProviderCatalogService(DSLContext dsl, ApplicationSecretService secrets,
                                    AiAdminPolicyService authorization,
                                    ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.secrets = secrets;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
    }

    public List<AiProviderView> list(ScoreUser actor) {
        authorization.requireAdministrator(actor);
        return dsl.selectFrom(AI_PROVIDER).orderBy(AI_PROVIDER.PROVIDER_NAME)
                .fetch(this::view);
    }

    public AiProviderView get(ScoreUser actor, long providerId) {
        authorization.requireAdministrator(actor);
        Record row = dsl.selectFrom(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(ULong.valueOf(providerId))).fetchOne();
        if (row == null) throw new NotFoundException();
        return view(row);
    }

    public AiProviderView create(ScoreUser actor, AiProviderUpdate input) {
        authorization.requireAdministrator(actor);
        validate(input, false);
        try {
            return dsl.transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            ULong actorId = actorId(actor);
            char[] key = chars(input.apiKey());
            ULong secretId = null;
            try {
                if (key != null) secretId = secrets.create(tx,
                        "ai-provider/" + input.providerName().strip() + "/api-key", key, actorId);
            } finally {
                ApplicationSecretService.clear(key);
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            ULong id = tx.insertInto(AI_PROVIDER)
                    .set(AI_PROVIDER.PROVIDER_NAME, input.providerName().strip())
                    .set(AI_PROVIDER.PROVIDER_TYPE, input.providerType().strip().toLowerCase())
                    .set(AI_PROVIDER.BASE_URL, nullable(input.baseUrl()))
                    .set(AI_PROVIDER.MESSAGES_URL, nullable(input.messagesUrl()))
                    .set(AI_PROVIDER.ANTHROPIC_VERSION, nullable(input.anthropicVersion()))
                    .set(AI_PROVIDER.API_VERSION, nullable(input.apiVersion()))
                    .set(AI_PROVIDER.API_KEY_SECRET_ID, secretId)
                    .set(AI_PROVIDER.ENABLED, flag(input.enabled()))
                    .set(AI_PROVIDER.CREATED_BY, actorId).set(AI_PROVIDER.LAST_UPDATED_BY, actorId)
                    .set(AI_PROVIDER.CREATED_AT, now).set(AI_PROVIDER.LAST_UPDATED_AT, now)
                    .returning(AI_PROVIDER.AI_PROVIDER_ID).fetchOne(AI_PROVIDER.AI_PROVIDER_ID);
            AiProviderView after = view(tx.selectFrom(AI_PROVIDER)
                    .where(AI_PROVIDER.AI_PROVIDER_ID.eq(id)).fetchOne());
            audit(tx, id, actorId, "CREATE", null, after, input.reason());
            return after;
            });
        } catch (org.jooq.exception.IntegrityConstraintViolationException exception) {
            throw conflict();
        }
    }

    public AiProviderView update(ScoreUser actor, long providerId, AiProviderUpdate input) {
        authorization.requireAdministrator(actor);
        validate(input, true);
        return dsl.transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            ULong id = ULong.valueOf(providerId);
            Record existing = tx.selectFrom(AI_PROVIDER)
                    .where(AI_PROVIDER.AI_PROVIDER_ID.eq(id)).forUpdate().fetchOne();
            if (existing == null) throw new NotFoundException();
            AiProviderView before = view(existing);
            if (before.catalogVersion() != input.expectedVersion()) throw conflict();
            if (!input.enabled() && before.enabled() && tx.fetchExists(tx.selectOne()
                    .from(AI_MODEL).where(AI_MODEL.PROVIDER_ID.eq(id))
                    .and(AI_MODEL.ENABLED.eq((byte) 1)))) {
                throw new IllegalArgumentException(
                        "Disable the provider's active models before disabling the provider.");
            }
            ULong actorId = actorId(actor);
            ULong oldSecretId = existing.get(AI_PROVIDER.API_KEY_SECRET_ID);
            ULong nextSecretId = updateSecret(tx, oldSecretId, input.providerName(),
                    input.apiKey(), actorId);
            int changed = tx.update(AI_PROVIDER)
                    .set(AI_PROVIDER.PROVIDER_NAME, input.providerName().strip())
                    .set(AI_PROVIDER.PROVIDER_TYPE, input.providerType().strip().toLowerCase())
                    .set(AI_PROVIDER.BASE_URL, nullable(input.baseUrl()))
                    .set(AI_PROVIDER.MESSAGES_URL, nullable(input.messagesUrl()))
                    .set(AI_PROVIDER.ANTHROPIC_VERSION, nullable(input.anthropicVersion()))
                    .set(AI_PROVIDER.API_VERSION, nullable(input.apiVersion()))
                    .set(AI_PROVIDER.API_KEY_SECRET_ID, nextSecretId)
                    .set(AI_PROVIDER.ENABLED, flag(input.enabled()))
                    .set(AI_PROVIDER.CATALOG_VERSION, AI_PROVIDER.CATALOG_VERSION.plus(1))
                    .set(AI_PROVIDER.LAST_UPDATED_BY, actorId)
                    .set(AI_PROVIDER.LAST_UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
                    .where(AI_PROVIDER.AI_PROVIDER_ID.eq(id))
                    .and(AI_PROVIDER.CATALOG_VERSION.eq(ULong.valueOf(input.expectedVersion())))
                    .execute();
            if (changed != 1) throw conflict();
            if (input.apiKey() != null && !StringUtils.hasText(input.apiKey())
                    && oldSecretId != null) {
                secrets.delete(tx, oldSecretId);
            }
            AiProviderView after = view(tx.selectFrom(AI_PROVIDER)
                    .where(AI_PROVIDER.AI_PROVIDER_ID.eq(id)).fetchOne());
            audit(tx, id, actorId, auditAction(input.apiKey(), oldSecretId != null, after.enabled()),
                    before, after, input.reason());
            return after;
        });
    }

    ULong updateSecret(DSLContext tx, ULong oldSecretId, String providerName,
                       String requestedKey, ULong actorId) {
        if (requestedKey == null) return oldSecretId;
        char[] key = chars(requestedKey);
        try {
            if (key == null) return null;
            if (oldSecretId == null) {
                return secrets.create(tx, "ai-provider/" + providerName.strip() + "/api-key",
                        key, actorId);
            }
            secrets.replace(tx, oldSecretId, key, actorId);
            return oldSecretId;
        } finally {
            ApplicationSecretService.clear(key);
        }
    }

    static String auditAction(String requestedKey, boolean hadStoredKey, boolean enabled) {
        if (StringUtils.hasText(requestedKey)) return "ROTATE_KEY";
        if (requestedKey != null && hadStoredKey) return "DELETE_KEY";
        return enabled ? "UPDATE" : "DISABLE";
    }

    private void validate(AiProviderUpdate input, boolean update) {
        if (input == null || !StringUtils.hasText(input.providerName())
                || !StringUtils.hasText(input.providerType())) {
            throw new IllegalArgumentException("Provider name and type are required.");
        }
        if (update && input.expectedVersion() == null) {
            throw new IllegalArgumentException("Expected catalog version is required.");
        }
        if (input.enabled() && !StringUtils.hasText(input.baseUrl())
                && !StringUtils.hasText(input.messagesUrl())) {
            throw new IllegalArgumentException("An enabled provider requires an endpoint URL.");
        }
        String type = input.providerType().strip().toLowerCase();
        if (!java.util.Set.of("anthropic", "azure-openai", "openai").contains(type)) {
            throw new IllegalArgumentException("Unsupported AI provider type: " + type);
        }
        validateEndpoint(input.baseUrl(), "base URL");
        validateEndpoint(input.messagesUrl(), "messages URL");
        requireReason(input.reason());
    }

    private static void validateEndpoint(String value, String label) {
        if (!StringUtils.hasText(value)) return;
        try {
            URI uri = URI.create(value.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException("Invalid AI provider " + label + ".");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid AI provider " + label + ".", exception);
        }
    }

    private AiProviderView view(Record row) {
        return new AiProviderView(row.get(AI_PROVIDER.AI_PROVIDER_ID).longValue(),
                row.get(AI_PROVIDER.PROVIDER_NAME), row.get(AI_PROVIDER.PROVIDER_TYPE),
                row.get(AI_PROVIDER.BASE_URL), row.get(AI_PROVIDER.MESSAGES_URL),
                row.get(AI_PROVIDER.ANTHROPIC_VERSION), row.get(AI_PROVIDER.API_VERSION),
                row.get(AI_PROVIDER.ENABLED) == 1,
                row.get(AI_PROVIDER.API_KEY_SECRET_ID) != null,
                row.get(AI_PROVIDER.CATALOG_VERSION).longValue());
    }

    private void audit(DSLContext tx, ULong entityId, ULong actorId, String action,
                       AiProviderView before, AiProviderView after, String reason) {
        tx.insertInto(AI_CATALOG_AUDIT)
                .set(AI_CATALOG_AUDIT.ENTITY_TYPE, "PROVIDER")
                .set(AI_CATALOG_AUDIT.ENTITY_ID, entityId)
                .set(AI_CATALOG_AUDIT.ACTOR_APP_USER_ID, actorId)
                .set(AI_CATALOG_AUDIT.ACTION, action)
                .set(AI_CATALOG_AUDIT.BEFORE_JSON, json(before))
                .set(AI_CATALOG_AUDIT.AFTER_JSON, json(after))
                .set(AI_CATALOG_AUDIT.REASON, reason.strip())
                .set(AI_CATALOG_AUDIT.CREATED_AT, LocalDateTime.now(ZoneOffset.UTC)).execute();
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize the catalog audit snapshot.", exception);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.strip().length() < 10) {
            throw new IllegalArgumentException("A change reason of at least 10 characters is required.");
        }
    }

    private static char[] chars(String value) {
        return StringUtils.hasText(value) ? value.toCharArray() : null;
    }

    private static String nullable(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static byte flag(boolean value) { return (byte) (value ? 1 : 0); }

    private static ULong actorId(ScoreUser actor) {
        return ULong.valueOf(actor.userId().value());
    }

    private static AiPolicyViolationException conflict() {
        return new AiPolicyViolationException(AiPolicyErrorCode.AI_CATALOG_VERSION_CONFLICT,
                "The provider catalog changed while it was being edited.");
    }
}

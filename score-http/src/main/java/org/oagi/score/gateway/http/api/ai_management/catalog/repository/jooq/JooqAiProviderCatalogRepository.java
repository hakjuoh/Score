package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderView;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiProviderCatalogRepository;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyErrorCode;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyViolationException;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.security.secret.AppSecretId;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CATALOG_AUDIT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

public class JooqAiProviderCatalogRepository extends JooqBaseRepository
        implements AiProviderCatalogRepository {

    private final ApplicationSecretService secrets;
    private final ObjectMapper objectMapper;

    public JooqAiProviderCatalogRepository(DSLContext dslContext,
                                           RepositoryFactory repositoryFactory,
                                           ApplicationSecretService secrets,
                                           ObjectMapper objectMapper) {
        super(dslContext, null, repositoryFactory);
        this.secrets = secrets;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AiProviderView> findAll() {
        return dslContext().selectFrom(AI_PROVIDER).orderBy(AI_PROVIDER.PROVIDER_NAME)
                .fetch(this::view);
    }

    @Override
    public Optional<AiProviderView> findById(AiProviderId providerId) {
        Record row = dslContext().selectFrom(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(providerId))).fetchOne();
        return Optional.ofNullable(row).map(this::view);
    }

    @Override
    public AiProviderView create(UserId actorUserId, AiProviderUpdate input) {
        try {
            return dslContext().transactionResult(configuration -> {
                DSLContext tx = org.jooq.impl.DSL.using(configuration);
                AppSecretId secretId = createSecret(
                        tx, input.providerName(), input.apiKey(), actorUserId);
                LocalDateTime now = now();
                AiProviderId id = new AiProviderId(tx.insertInto(AI_PROVIDER)
                        .set(AI_PROVIDER.PROVIDER_NAME, input.providerName().strip())
                        .set(AI_PROVIDER.PROVIDER_TYPE,
                                input.providerType().strip().toLowerCase())
                        .set(AI_PROVIDER.BASE_URL, nullable(input.baseUrl()))
                        .set(AI_PROVIDER.MESSAGES_URL, nullable(input.messagesUrl()))
                        .set(AI_PROVIDER.ANTHROPIC_VERSION,
                                nullable(input.anthropicVersion()))
                        .set(AI_PROVIDER.API_VERSION, nullable(input.apiVersion()))
                        .set(AI_PROVIDER.API_KEY_SECRET_ID, valueOf(secretId))
                        .set(AI_PROVIDER.ENABLED, flag(input.enabled()))
                        .set(AI_PROVIDER.CREATED_BY, valueOf(actorUserId))
                        .set(AI_PROVIDER.LAST_UPDATED_BY, valueOf(actorUserId))
                        .set(AI_PROVIDER.CREATED_AT, now)
                        .set(AI_PROVIDER.LAST_UPDATED_AT, now)
                        .returning(AI_PROVIDER.AI_PROVIDER_ID)
                        .fetchOne(AI_PROVIDER.AI_PROVIDER_ID).toBigInteger());
                AiProviderView after = view(requireProvider(tx, id));
                audit(tx, id, actorUserId, "CREATE", null, after);
                return after;
            });
        } catch (org.jooq.exception.IntegrityConstraintViolationException exception) {
            throw conflict();
        }
    }

    @Override
    public AiProviderView update(UserId actorUserId, AiProviderId providerId,
                                 AiProviderUpdate input) {
        return dslContext().transactionResult(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            Record existing = tx.selectFrom(AI_PROVIDER)
                    .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(providerId)))
                    .forUpdate().fetchOne();
            if (existing == null) throw new NotFoundException();
            AiProviderView before = view(existing);
            if (before.catalogVersion() != input.expectedVersion()) throw conflict();
            requireCompatibleProviderFamily(
                    tx, providerId, before.providerType(), input.providerType());
            if (!input.enabled() && before.enabled() && tx.fetchExists(tx.selectOne()
                    .from(AI_MODEL).where(AI_MODEL.PROVIDER_ID.eq(valueOf(providerId)))
                    .and(AI_MODEL.ENABLED.eq((byte) 1)))) {
                throw new IllegalArgumentException(
                        "Disable the provider's active models before disabling the provider.");
            }
            AppSecretId oldSecretId = existing.get(AI_PROVIDER.API_KEY_SECRET_ID) != null
                    ? new AppSecretId(existing.get(
                            AI_PROVIDER.API_KEY_SECRET_ID).toBigInteger()) : null;
            AppSecretId nextSecretId = updateSecret(tx, oldSecretId, input.providerName(),
                    input.apiKey(), actorUserId);
            int changed = tx.update(AI_PROVIDER)
                    .set(AI_PROVIDER.PROVIDER_NAME, input.providerName().strip())
                    .set(AI_PROVIDER.PROVIDER_TYPE,
                            input.providerType().strip().toLowerCase())
                    .set(AI_PROVIDER.BASE_URL, nullable(input.baseUrl()))
                    .set(AI_PROVIDER.MESSAGES_URL, nullable(input.messagesUrl()))
                    .set(AI_PROVIDER.ANTHROPIC_VERSION, nullable(input.anthropicVersion()))
                    .set(AI_PROVIDER.API_VERSION, nullable(input.apiVersion()))
                    .set(AI_PROVIDER.API_KEY_SECRET_ID, valueOf(nextSecretId))
                    .set(AI_PROVIDER.ENABLED, flag(input.enabled()))
                    .set(AI_PROVIDER.CATALOG_VERSION, AI_PROVIDER.CATALOG_VERSION.plus(1))
                    .set(AI_PROVIDER.LAST_UPDATED_BY, valueOf(actorUserId))
                    .set(AI_PROVIDER.LAST_UPDATED_AT, now())
                    .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(providerId)))
                    .and(AI_PROVIDER.CATALOG_VERSION.eq(
                            ULong.valueOf(input.expectedVersion())))
                    .execute();
            if (changed != 1) throw conflict();
            if (input.apiKey() != null && !StringUtils.hasText(input.apiKey())
                    && oldSecretId != null) {
                secrets.delete(tx, valueOf(oldSecretId));
            }
            AiProviderView after = view(requireProvider(tx, providerId));
            audit(tx, providerId, actorUserId,
                    auditAction(input.apiKey(), oldSecretId != null, after.enabled()),
                    before, after);
            return after;
        });
    }

    @Override
    public Optional<ConnectionDetails> findConnectionDetails(AiProviderId providerId) {
        Record row = dslContext().selectFrom(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(providerId))).fetchOne();
        if (row == null) return Optional.empty();
        AppSecretId secretId = row.get(AI_PROVIDER.API_KEY_SECRET_ID) != null
                ? new AppSecretId(row.get(AI_PROVIDER.API_KEY_SECRET_ID).toBigInteger()) : null;
        return Optional.of(new ConnectionDetails(
                row.get(AI_PROVIDER.CATALOG_VERSION).longValue(),
                row.get(AI_PROVIDER.PROVIDER_TYPE), row.get(AI_PROVIDER.BASE_URL),
                row.get(AI_PROVIDER.MESSAGES_URL),
                secretId));
    }

    @Override
    public char[] loadConnectionTestKey(AppSecretId storedSecretId, String requestedKey) {
        if (requestedKey != null) return chars(requestedKey);
        if (storedSecretId == null) return null;
        if (!secrets.isEncryptionConfigured()) {
            throw new IllegalStateException("Provider secret encryption is not configured.");
        }
        return secrets.decrypt(dslContext(), valueOf(storedSecretId));
    }

    @Override
    public String findConnectionTestModel(AiProviderId providerId) {
        return dslContext().select(AI_MODEL.PROVIDER_MODEL_NAME).from(AI_MODEL)
                .where(AI_MODEL.PROVIDER_ID.eq(valueOf(providerId)))
                .orderBy(AI_MODEL.ENABLED.desc(), AI_MODEL.SORT_ORDER, AI_MODEL.AI_MODEL_ID)
                .limit(1).fetchOne(AI_MODEL.PROVIDER_MODEL_NAME);
    }

    AppSecretId updateSecret(DSLContext tx, AppSecretId oldSecretId, String providerName,
                             String requestedKey, UserId actorUserId) {
        if (requestedKey == null) return oldSecretId;
        char[] key = chars(requestedKey);
        try {
            if (key == null) return null;
            if (oldSecretId == null) {
                return new AppSecretId(secrets.create(tx, secretName(providerName), key,
                        valueOf(actorUserId)).toBigInteger());
            }
            secrets.replace(tx, valueOf(oldSecretId), key, valueOf(actorUserId));
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

    static void requireCompatibleProviderFamily(DSLContext tx, AiProviderId providerId,
                                                String currentType, String requestedType) {
        if (AiProviderType.from(currentType) == AiProviderType.from(requestedType)) return;
        if (tx.fetchCount(AI_MODEL,
                AI_MODEL.PROVIDER_ID.eq(org.jooq.types.ULong.valueOf(providerId.value()))) > 0) {
            throw new IllegalArgumentException(
                    "Remove the provider's models before changing its provider family.");
        }
    }

    private AppSecretId createSecret(DSLContext tx, String providerName, String requestedKey,
                                     UserId actorUserId) {
        char[] key = chars(requestedKey);
        try {
            return key != null ? new AppSecretId(secrets.create(tx, secretName(providerName), key,
                    valueOf(actorUserId)).toBigInteger()) : null;
        } finally {
            ApplicationSecretService.clear(key);
        }
    }

    private static String secretName(String providerName) {
        return "ai-provider/" + providerName.strip() + "/api-key";
    }

    private Record requireProvider(DSLContext tx, AiProviderId id) {
        Record row = tx.selectFrom(AI_PROVIDER)
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(id))).fetchOne();
        if (row == null) throw new NotFoundException();
        return row;
    }

    private AiProviderView view(Record row) {
        return new AiProviderView(
                new AiProviderId(row.get(AI_PROVIDER.AI_PROVIDER_ID).toBigInteger()),
                row.get(AI_PROVIDER.PROVIDER_NAME), row.get(AI_PROVIDER.PROVIDER_TYPE),
                row.get(AI_PROVIDER.BASE_URL), row.get(AI_PROVIDER.MESSAGES_URL),
                row.get(AI_PROVIDER.ANTHROPIC_VERSION), row.get(AI_PROVIDER.API_VERSION),
                row.get(AI_PROVIDER.ENABLED) == 1,
                row.get(AI_PROVIDER.API_KEY_SECRET_ID) != null,
                row.get(AI_PROVIDER.CATALOG_VERSION).longValue());
    }

    private void audit(DSLContext tx, AiProviderId entityId, UserId actorUserId, String action,
                       AiProviderView before, AiProviderView after) {
        tx.insertInto(AI_CATALOG_AUDIT)
                .set(AI_CATALOG_AUDIT.ENTITY_TYPE, "PROVIDER")
                .set(AI_CATALOG_AUDIT.ENTITY_ID, valueOf(entityId))
                .set(AI_CATALOG_AUDIT.ACTOR_APP_USER_ID, valueOf(actorUserId))
                .set(AI_CATALOG_AUDIT.ACTION, action)
                .set(AI_CATALOG_AUDIT.BEFORE_JSON, json(before))
                .set(AI_CATALOG_AUDIT.AFTER_JSON, json(after))
                .set(AI_CATALOG_AUDIT.CREATED_AT, now()).execute();
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize the catalog audit snapshot.", exception);
        }
    }

    private static char[] chars(String value) {
        return StringUtils.hasText(value) ? value.toCharArray() : null;
    }

    private static String nullable(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static byte flag(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static AiPolicyViolationException conflict() {
        return new AiPolicyViolationException(AiPolicyErrorCode.AI_CATALOG_VERSION_CONFLICT,
                "The provider catalog changed while it was being edited.");
    }
}

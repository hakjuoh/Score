package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderType;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderView;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiProviderCatalogRepository;
import org.oagi.score.gateway.http.api.ai_management.AiAdminPage;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.security.secret.AppSecretId;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.PageResponse;
import org.oagi.score.gateway.http.common.model.SortDirection;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.APP_USER;

public class JooqAiProviderCatalogRepository extends JooqBaseRepository
        implements AiProviderCatalogRepository {

    private static final org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AppUser
            UPDATER = APP_USER.as("updater");
    private static final Field<String> UPDATER_LOGIN_ID =
            UPDATER.LOGIN_ID.as("updater_login_id");

    private final ApplicationSecretService secrets;

    public JooqAiProviderCatalogRepository(DSLContext dslContext,
                                           RepositoryFactory repositoryFactory,
                                           ApplicationSecretService secrets) {
        super(dslContext, null, repositoryFactory);
        this.secrets = secrets;
    }

    @Override
    public List<AiProviderView> findAll() {
        return dslContext().select(AI_PROVIDER.fields()).select(UPDATER_LOGIN_ID)
                .from(AI_PROVIDER).leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_PROVIDER.LAST_UPDATED_BY))
                .orderBy(AI_PROVIDER.PROVIDER_NAME)
                .fetch(this::view);
    }

    @Override
    public PageResponse<AiProviderView> search(String name, String type, String endpoint,
                                               Boolean enabled, List<String> updaterLoginIdList,
                                               Instant updatedAfter, Instant updatedBefore,
                                               PageRequest pageRequest) {
        AiAdminPage.validate(pageRequest);
        Condition condition = DSL.trueCondition();
        if (StringUtils.hasText(name)) {
            condition = condition.and(AI_PROVIDER.PROVIDER_NAME.containsIgnoreCase(name.strip()));
        }
        if (StringUtils.hasText(type)) {
            condition = condition.and(AI_PROVIDER.PROVIDER_TYPE.containsIgnoreCase(type.strip()));
        }
        if (StringUtils.hasText(endpoint)) {
            String value = endpoint.strip();
            condition = condition.and(AI_PROVIDER.BASE_URL.containsIgnoreCase(value)
                    .or(AI_PROVIDER.MESSAGES_URL.containsIgnoreCase(value)));
        }
        if (enabled != null) {
            condition = condition.and(AI_PROVIDER.ENABLED.eq(flag(enabled)));
        }
        condition = condition.and(AiAdminPage.loginIdSelection(
                UPDATER.LOGIN_ID, updaterLoginIdList));
        if (updatedAfter != null) {
            condition = condition.and(AI_PROVIDER.LAST_UPDATE_TIMESTAMP.ge(
                    updatedAfter.atZone(ZoneOffset.UTC).toLocalDateTime()));
        }
        if (updatedBefore != null) {
            condition = condition.and(AI_PROVIDER.LAST_UPDATE_TIMESTAMP.lt(
                    updatedBefore.atZone(ZoneOffset.UTC).toLocalDateTime()));
        }
        var candidates = DSL.selectOne().from(AI_PROVIDER).leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_PROVIDER.LAST_UPDATED_BY)).where(condition);
        int total = dslContext().fetchCount(candidates);
        long offset = AiAdminPage.offset(pageRequest);
        if (offset >= total) {
            return new PageResponse<>(List.of(), pageRequest.pageIndex(),
                    pageRequest.pageSize(), total);
        }
        Field<String> endpointField = DSL.coalesce(AI_PROVIDER.BASE_URL,
                AI_PROVIDER.MESSAGES_URL);
        List<SortField<?>> order = new ArrayList<>();
        pageRequest.sorts().forEach(sort -> {
            Field<?> field = switch (sort.field()) {
                case "name" -> AI_PROVIDER.PROVIDER_NAME;
                case "type" -> AI_PROVIDER.PROVIDER_TYPE;
                case "endpoint" -> endpointField;
                case "status" -> AI_PROVIDER.ENABLED;
                case "updater" -> UPDATER.LOGIN_ID;
                case "updatedOn" -> AI_PROVIDER.LAST_UPDATE_TIMESTAMP;
                default -> null;
            };
            if (field != null) order.add(sort.direction() == SortDirection.DESC
                    ? field.desc() : field.asc());
        });
        if (order.isEmpty()) order.add(AI_PROVIDER.LAST_UPDATE_TIMESTAMP.desc());
        order.add(AI_PROVIDER.AI_PROVIDER_ID.asc());
        List<AiProviderView> page = dslContext().select(AI_PROVIDER.fields())
                .select(UPDATER_LOGIN_ID).from(AI_PROVIDER).leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_PROVIDER.LAST_UPDATED_BY))
                .where(condition).orderBy(order).limit((int) offset, pageRequest.pageSize())
                .fetch(this::view);
        return new PageResponse<>(page, pageRequest.pageIndex(), pageRequest.pageSize(), total);
    }

    @Override
    public Optional<AiProviderView> findById(AiProviderId providerId) {
        Record row = dslContext().select(AI_PROVIDER.fields()).select(UPDATER_LOGIN_ID)
                .from(AI_PROVIDER).leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_PROVIDER.LAST_UPDATED_BY))
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
                        .set(AI_PROVIDER.API_VERSION, nullable(input.apiVersion()))
                        .set(AI_PROVIDER.API_KEY_SECRET_ID, valueOf(secretId))
                        .set(AI_PROVIDER.ENABLED, flag(input.enabled()))
                        .set(AI_PROVIDER.CREATED_BY, valueOf(actorUserId))
                        .set(AI_PROVIDER.LAST_UPDATED_BY, valueOf(actorUserId))
                        .set(AI_PROVIDER.CREATION_TIMESTAMP, now)
                        .set(AI_PROVIDER.LAST_UPDATE_TIMESTAMP, now)
                        .returning(AI_PROVIDER.AI_PROVIDER_ID)
                        .fetchOne(AI_PROVIDER.AI_PROVIDER_ID).toBigInteger());
                return view(requireProvider(tx, id));
            });
        } catch (org.jooq.exception.IntegrityConstraintViolationException exception) {
            throw new IllegalArgumentException("A provider with this name already exists.", exception);
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
            requireCompatibleProviderFamily(
                    tx, providerId, existing.get(AI_PROVIDER.PROVIDER_TYPE), input.providerType());
            if (!input.enabled() && existing.get(AI_PROVIDER.ENABLED) == 1
                    && tx.fetchExists(tx.selectOne()
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
                    .set(AI_PROVIDER.API_VERSION, nullable(input.apiVersion()))
                    .set(AI_PROVIDER.API_KEY_SECRET_ID, valueOf(nextSecretId))
                    .set(AI_PROVIDER.ENABLED, flag(input.enabled()))
                    .set(AI_PROVIDER.LAST_UPDATED_BY, valueOf(actorUserId))
                    .set(AI_PROVIDER.LAST_UPDATE_TIMESTAMP, now())
                    .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(providerId)))
                    .execute();
            if (changed != 1) throw new NotFoundException();
            if (input.apiKey() != null && !StringUtils.hasText(input.apiKey())
                    && oldSecretId != null) {
                secrets.delete(tx, valueOf(oldSecretId));
            }
            return view(requireProvider(tx, providerId));
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
                row.get(AI_PROVIDER.PROVIDER_TYPE), row.get(AI_PROVIDER.BASE_URL),
                row.get(AI_PROVIDER.MESSAGES_URL),
                secretId));
    }

    @Override
    public char[] loadConnectionTestKey(AppSecretId storedSecretId, String requestedKey) {
        if (requestedKey != null) return chars(requestedKey);
        return loadStoredApiKey(storedSecretId);
    }

    @Override
    public char[] loadStoredApiKey(AppSecretId storedSecretId) {
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
        Record row = tx.select(AI_PROVIDER.fields()).select(UPDATER_LOGIN_ID)
                .from(AI_PROVIDER).leftJoin(UPDATER)
                .on(UPDATER.APP_USER_ID.eq(AI_PROVIDER.LAST_UPDATED_BY))
                .where(AI_PROVIDER.AI_PROVIDER_ID.eq(valueOf(id))).fetchOne();
        if (row == null) throw new NotFoundException();
        return row;
    }

    private AiProviderView view(Record row) {
        return new AiProviderView(
                new AiProviderId(row.get(AI_PROVIDER.AI_PROVIDER_ID).toBigInteger()),
                row.get(AI_PROVIDER.PROVIDER_NAME), row.get(AI_PROVIDER.PROVIDER_TYPE),
                row.get(AI_PROVIDER.BASE_URL), row.get(AI_PROVIDER.MESSAGES_URL),
                row.get(AI_PROVIDER.API_VERSION),
                row.get(AI_PROVIDER.ENABLED) == 1,
                row.get(AI_PROVIDER.API_KEY_SECRET_ID) != null,
                updaterLoginId(row), utc(row.get(AI_PROVIDER.LAST_UPDATE_TIMESTAMP)));
    }

    private String updaterLoginId(Record row) {
        if (row.indexOf(UPDATER_LOGIN_ID) >= 0) return row.get(UPDATER_LOGIN_ID);
        var updaterId = row.get(AI_PROVIDER.LAST_UPDATED_BY);
        return updaterId != null ? dslContext().select(APP_USER.LOGIN_ID).from(APP_USER)
                .where(APP_USER.APP_USER_ID.eq(updaterId)).fetchOne(APP_USER.LOGIN_ID) : null;
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

    private static Instant utc(LocalDateTime value) {
        return value != null ? value.toInstant(ZoneOffset.UTC) : null;
    }

}

package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.Sort;
import org.oagi.score.gateway.http.common.model.SortDirection;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.security.secret.AppSecretId;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

class JooqAiProviderCatalogRepositoryTest {

    @Test
    void searchesUpdaterDateRangeWithStableUpdatedOnPagination() {
        List<String> statements = new ArrayList<>();
        DSLContext create = DSL.using(SQLDialect.MARIADB);
        Field<String> updaterLoginId = DSL.field(
                DSL.name("updater_login_id"), String.class);
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            statements.add(context.sql());
            if (context.sql().contains("count(*)")) {
                Field<Integer> count = DSL.field("count", Integer.class);
                var result = create.newResult(count);
                var record = create.newRecord(count);
                record.set(count, 1);
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            Field<?>[] fields = Stream.concat(Arrays.stream(AI_PROVIDER.fields()),
                    Stream.of(updaterLoginId)).toArray(Field<?>[]::new);
            Result<Record> result = create.newResult(fields);
            Record record = create.newRecord(fields);
            record.set(AI_PROVIDER.AI_PROVIDER_ID, ULong.valueOf(7));
            record.set(AI_PROVIDER.PROVIDER_NAME, "Anthropic");
            record.set(AI_PROVIDER.PROVIDER_TYPE, "anthropic");
            record.set(AI_PROVIDER.ENABLED, (byte) 1);
            record.set(AI_PROVIDER.LAST_UPDATED_AT,
                    LocalDateTime.of(2026, 7, 15, 12, 0));
            record.set(updaterLoginId, "admin");
            result.add(record);
            return new MockResult[]{new MockResult(1, result)};
        }), SQLDialect.MARIADB);
        var repository = new JooqAiProviderCatalogRepository(
                dsl, mock(RepositoryFactory.class), mock(ApplicationSecretService.class));

        var response = repository.search("anth", "anthropic", "messages", true,
                List.of("admin", "!reviewer"), Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"), new PageRequest(0, 25,
                        List.of(new Sort("updatedOn", SortDirection.DESC))));

        assertThat(response.getList()).singleElement().satisfies(provider -> {
            assertThat(provider.providerName()).isEqualTo("Anthropic");
            assertThat(provider.updaterLoginId()).isEqualTo("admin");
        });
        assertThat(response.getLength()).isEqualTo(1);
        String sql = String.join("\n", statements);
        assertThat(sql).contains("`updater`.`login_id` in (?)")
                .contains("`updater`.`login_id` not in (?)")
                .contains("`ai_provider`.`last_updated_at` >= ?")
                .contains("`ai_provider`.`last_updated_at` < ?")
                .contains("order by `oagi`.`ai_provider`.`last_updated_at` desc")
                .contains("offset ? rows fetch next ? rows only");
    }

    @Test
    void providerUpdateKeepsReplacesAndClearsTheWriteOnlyKeyByPayloadState() {
        DSLContext dsl = mock(DSLContext.class);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        var repository = new JooqAiProviderCatalogRepository(
                dsl, mock(RepositoryFactory.class), secrets);
        AppSecretId oldSecretId = AppSecretId.from(9L);
        ULong storedOldSecretId = ULong.valueOf(9);
        ULong newSecretId = ULong.valueOf(10);
        when(secrets.create(any(), anyString(), any(), any())).thenReturn(newSecretId);

        assertThat(repository.updateSecret(dsl, oldSecretId, "provider", null, null))
                .isEqualTo(oldSecretId);
        assertThat(repository.updateSecret(dsl, oldSecretId, "provider", "", null)).isNull();
        assertThat(repository.updateSecret(dsl, null, "provider", "new-key", null))
                .isEqualTo(AppSecretId.from(10L));
        assertThat(repository.updateSecret(
                dsl, oldSecretId, "provider", "replacement", null))
                .isEqualTo(oldSecretId);

        verify(secrets).create(any(), anyString(), any(), any());
        verify(secrets).replace(any(), eq(storedOldSecretId), any(), any());
        verify(secrets, never()).delete(any(), any());
    }

    @Test
    void connectionTestUsesADraftKeyOrDecryptsTheStoredKeyWithoutPersistingIt() {
        DSLContext dsl = mock(DSLContext.class);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        var repository = new JooqAiProviderCatalogRepository(
                dsl, mock(RepositoryFactory.class), secrets);
        ULong storedSecretId = ULong.valueOf(9);
        when(secrets.isEncryptionConfigured()).thenReturn(true);
        when(secrets.decrypt(dsl, storedSecretId)).thenReturn("stored-key".toCharArray());

        AppSecretId secretId = AppSecretId.from(9L);
        assertThat(repository.loadConnectionTestKey(secretId, "draft-key"))
                .containsExactly("draft-key".toCharArray());
        assertThat(repository.loadConnectionTestKey(secretId, null))
                .containsExactly("stored-key".toCharArray());
        assertThat(repository.loadConnectionTestKey(secretId, "")).isNull();
        assertThat(repository.loadConnectionTestKey(secretId, "   ")).isNull();

        verify(secrets).decrypt(dsl, storedSecretId);
    }

    @Test
    void rejectsProviderFamilyChangesWhileModelsRemainLinked() {
        DSLContext dsl = mock(DSLContext.class);
        AiProviderId providerId = AiProviderId.from(7L);
        when(dsl.fetchCount(eq(AI_MODEL), any(Condition.class))).thenReturn(1);

        assertThatThrownBy(() ->
                JooqAiProviderCatalogRepository.requireCompatibleProviderFamily(
                        dsl, providerId, "anthropic", "openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Remove the provider's models");
    }

    @Test
    void permitsEmptyFamilyChangesAndRejectsRemovedProviderTypes() {
        DSLContext dsl = mock(DSLContext.class);
        AiProviderId providerId = AiProviderId.from(7L);

        assertThatThrownBy(() -> JooqAiProviderCatalogRepository.requireCompatibleProviderFamily(
                dsl, providerId, "azure-openai", "openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported AI provider type");
        verify(dsl, never()).fetchCount(eq(AI_MODEL), any(Condition.class));

        when(dsl.fetchCount(eq(AI_MODEL), any(Condition.class))).thenReturn(0);
        JooqAiProviderCatalogRepository.requireCompatibleProviderFamily(
                dsl, providerId, "anthropic", "openai");
    }
}

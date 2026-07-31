package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiProviderRecord;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

class AiCatalogSecretAvailabilityTest {

    @Test
    void excludesEncryptedProvidersWithoutAttemptingDecryptionWhenKeyIsAbsent() {
        RecordingProvider database = new RecordingProvider(true);
        DSLContext dsl = DSL.using(new MockConnection(database), SQLDialect.MARIADB);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        when(secrets.isEncryptionConfigured()).thenReturn(false);
        ScoreAiProperties properties = new ScoreAiProperties();

        new AiDatabaseCatalogLoader(new RepositoryFactory(dsl), secrets, new ObjectMapper())
                .loadInto(properties);

        verify(secrets, never()).decrypt(dsl, ULong.valueOf(99));
        assertThat(properties.getProviders()).isEmpty();
        assertThat(properties.getModels()).isEmpty();
    }

    @Test
    void defersPlaintextCatalogBootstrapWhenEncryptionKeyIsAbsent() {
        RecordingProvider database = new RecordingProvider(false);
        DSLContext dsl = DSL.using(new MockConnection(database), SQLDialect.MARIADB);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        when(secrets.isEncryptionConfigured()).thenReturn(false);
        ScoreAiProperties properties = bootstrapProperties();

        new AiCatalogBootstrap(new RepositoryFactory(dsl), properties, secrets).bootstrapNow();

        assertThat(database.sql).noneMatch(statement -> statement.startsWith("insert into"));
        verify(secrets, never()).create(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void importsPlaintextCatalogAfterEncryptionKeyIsConfigured() {
        RecordingProvider database = new RecordingProvider(false);
        DSLContext dsl = DSL.using(new MockConnection(database), SQLDialect.MARIADB);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        when(secrets.isEncryptionConfigured()).thenReturn(true);
        when(secrets.create(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(ULong.valueOf(99));

        new AiCatalogBootstrap(new RepositoryFactory(dsl), bootstrapProperties(), secrets)
                .bootstrapNow();

        assertThat(database.sql).anyMatch(statement -> statement.startsWith("insert into")
                && statement.contains("ai_provider"));
        assertThat(database.sql).anyMatch(statement -> statement.startsWith("insert into")
                && statement.contains("ai_model"));
        verify(secrets).create(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.contains("ai-provider/provider/api-key"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void waitsForDatabaseInitializationBeforeCatalogAccess() {
        assertThat(AiCatalogBootstrap.class.isAnnotationPresent(
                DependsOnDatabaseInitialization.class)).isTrue();
    }

    private ScoreAiProperties bootstrapProperties() {
        ScoreAiProperties.Provider provider = new ScoreAiProperties.Provider();
        provider.setType("openai");
        provider.setBaseUrl("https://example.test");
        provider.setKey("plaintext-provider-key");
        ScoreAiProperties.Model model = new ScoreAiProperties.Model();
        model.setProvider("provider");
        model.setContextWindow(128000L);
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.setProviders(new LinkedHashMap<>(Map.of("provider", provider)));
        properties.setModels(new LinkedHashMap<>(Map.of("model", model)));
        return properties;
    }

    private static final class RecordingProvider implements MockDataProvider {
        private final boolean encryptedProvider;
        private final List<String> sql = new ArrayList<>();

        private RecordingProvider(boolean encryptedProvider) {
            this.encryptedProvider = encryptedProvider;
        }

        @Override
        public MockResult[] execute(org.jooq.tools.jdbc.MockExecuteContext context) {
            String statement = context.sql().toLowerCase();
            sql.add(statement);
            DSLContext create = DSL.using(SQLDialect.MARIADB);
            if (statement.startsWith("select count")) {
                Field<Integer> count = DSL.field("count", Integer.class);
                Result<Record1<Integer>> result = create.newResult(count);
                Record1<Integer> record = create.newRecord(count);
                record.value1(0);
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            if (encryptedProvider && statement.contains("from `ai_provider`")) {
                Result<AiProviderRecord> result = create.newResult(AI_PROVIDER);
                AiProviderRecord record = create.newRecord(AI_PROVIDER);
                record.setAiProviderId(ULong.valueOf(1));
                record.setApiKeySecretId(ULong.valueOf(99));
                record.setEnabled((byte) 1);
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            if (statement.startsWith("insert into") && statement.contains("ai_provider")) {
                return id(create, AI_PROVIDER.AI_PROVIDER_ID, 1);
            }
            if (statement.startsWith("insert into") && statement.contains("ai_model")) {
                return id(create, AI_MODEL.AI_MODEL_ID, 2);
            }
            if (statement.startsWith("insert into")) {
                return new MockResult[]{new MockResult(1, null)};
            }
            return new MockResult[]{new MockResult(0, create.newResult())};
        }

        private MockResult[] id(DSLContext create, Field<ULong> field, long value) {
            Result<Record1<ULong>> result = create.newResult(field);
            Record1<ULong> record = create.newRecord(field);
            record.value1(ULong.valueOf(value));
            result.add(record);
            return new MockResult[]{new MockResult(1, result)};
        }
    }
}

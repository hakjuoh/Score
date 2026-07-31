package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.security.secret.AppSecretId;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

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

class JooqAiProviderCatalogRepositoryTest {

    @Test
    void providerUpdateKeepsReplacesAndClearsTheWriteOnlyKeyByPayloadState() {
        DSLContext dsl = mock(DSLContext.class);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        var repository = new JooqAiProviderCatalogRepository(
                dsl, mock(RepositoryFactory.class), secrets, new ObjectMapper());
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
    void providerUpdateAuditsCredentialChangesThroughTheUnifiedUpdateAction() {
        assertThat(JooqAiProviderCatalogRepository.auditAction(null, true, true))
                .isEqualTo("UPDATE");
        assertThat(JooqAiProviderCatalogRepository.auditAction(null, false, false))
                .isEqualTo("DISABLE");
        assertThat(JooqAiProviderCatalogRepository.auditAction("replacement", true, true))
                .isEqualTo("ROTATE_KEY");
        assertThat(JooqAiProviderCatalogRepository.auditAction("", true, true))
                .isEqualTo("DELETE_KEY");
        assertThat(JooqAiProviderCatalogRepository.auditAction("", false, true))
                .isEqualTo("UPDATE");
    }

    @Test
    void connectionTestUsesADraftKeyOrDecryptsTheStoredKeyWithoutPersistingIt() {
        DSLContext dsl = mock(DSLContext.class);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        var repository = new JooqAiProviderCatalogRepository(
                dsl, mock(RepositoryFactory.class), secrets, new ObjectMapper());
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
    void permitsProviderAliasesAndEmptyFamilyChanges() {
        DSLContext dsl = mock(DSLContext.class);
        AiProviderId providerId = AiProviderId.from(7L);

        JooqAiProviderCatalogRepository.requireCompatibleProviderFamily(
                dsl, providerId, "azure-openai", "openai");
        verify(dsl, never()).fetchCount(eq(AI_MODEL), any(Condition.class));

        when(dsl.fetchCount(eq(AI_MODEL), any(Condition.class))).thenReturn(0);
        JooqAiProviderCatalogRepository.requireCompatibleProviderFamily(
                dsl, providerId, "anthropic", "openai");
    }
}

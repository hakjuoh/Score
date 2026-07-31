package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCatalogAdminValidationTest {

    private final ScoreUser actor = mock(ScoreUser.class);

    @Test
    void rejectsInvalidModelCommandsBeforeOpeningATransaction() {
        AiModelCatalogAdminService service = new AiModelCatalogAdminService(
                mock(DSLContext.class), mock(AiAdminPolicyService.class), new ObjectMapper());

        assertThatThrownBy(() -> service.create(actor,
                new AiModelCatalogUpdate(null, 1L, "gpt-5_6-sol", true, false, -1,
                        4096, 128000L, 4096L, 100000L, 4096L, 16000L,
                        false, null, null, false, null, null,
                        true, false, true, false, List.of(), null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort order");
    }

    @Test
    void rejectsUnsupportedProviderTypesAndMalformedEndpoints() {
        AiProviderCatalogService service = new AiProviderCatalogService(mock(DSLContext.class),
                mock(ApplicationSecretService.class), mock(AiAdminPolicyService.class),
                new ObjectMapper(), mock(AiProviderConnectionTester.class));

        assertThatThrownBy(() -> service.create(actor, new AiProviderUpdate(null,
                "provider", "unsupported", "https://example.test", null,
                null, null, true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> service.create(actor, new AiProviderUpdate(null,
                "provider", "openai", "not a URL", null,
                null, null, true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid AI provider base URL");
    }

    @Test
    void providerUpdateKeepsReplacesAndClearsTheWriteOnlyKeyByPayloadState() {
        DSLContext dsl = mock(DSLContext.class);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        AiProviderCatalogService service = new AiProviderCatalogService(dsl, secrets,
                mock(AiAdminPolicyService.class), new ObjectMapper(),
                mock(AiProviderConnectionTester.class));
        ULong oldSecretId = ULong.valueOf(9);
        ULong newSecretId = ULong.valueOf(10);
        when(secrets.create(any(), anyString(), any(), any())).thenReturn(newSecretId);

        assertThat(service.updateSecret(dsl, oldSecretId, "provider", null, null))
                .isEqualTo(oldSecretId);
        assertThat(service.updateSecret(dsl, oldSecretId, "provider", "", null)).isNull();
        assertThat(service.updateSecret(dsl, null, "provider", "new-key", null))
                .isEqualTo(newSecretId);
        assertThat(service.updateSecret(dsl, oldSecretId, "provider", "replacement", null))
                .isEqualTo(oldSecretId);

        verify(secrets).create(any(), anyString(), any(), any());
        verify(secrets).replace(any(), org.mockito.ArgumentMatchers.eq(oldSecretId), any(), any());
        verify(secrets, never()).delete(any(), any());
    }

    @Test
    void providerUpdateAuditsCredentialChangesThroughTheUnifiedUpdateAction() {
        assertThat(AiProviderCatalogService.auditAction(null, true, true)).isEqualTo("UPDATE");
        assertThat(AiProviderCatalogService.auditAction(null, false, false)).isEqualTo("DISABLE");
        assertThat(AiProviderCatalogService.auditAction("replacement", true, true))
                .isEqualTo("ROTATE_KEY");
        assertThat(AiProviderCatalogService.auditAction("new-key", false, true))
                .isEqualTo("ROTATE_KEY");
        assertThat(AiProviderCatalogService.auditAction("", true, true)).isEqualTo("DELETE_KEY");
        assertThat(AiProviderCatalogService.auditAction("", false, true)).isEqualTo("UPDATE");
    }

    @Test
    void connectionTestUsesADraftKeyOrDecryptsTheStoredKeyWithoutPersistingIt() {
        DSLContext dsl = mock(DSLContext.class);
        ApplicationSecretService secrets = mock(ApplicationSecretService.class);
        AiProviderCatalogService service = new AiProviderCatalogService(dsl, secrets,
                mock(AiAdminPolicyService.class), new ObjectMapper(),
                mock(AiProviderConnectionTester.class));
        ULong storedSecretId = ULong.valueOf(9);
        when(secrets.isEncryptionConfigured()).thenReturn(true);
        when(secrets.decrypt(dsl, storedSecretId)).thenReturn("stored-key".toCharArray());

        assertThat(service.connectionTestKey(storedSecretId, "draft-key"))
                .containsExactly("draft-key".toCharArray());
        assertThat(service.connectionTestKey(storedSecretId, null))
                .containsExactly("stored-key".toCharArray());
        assertThat(service.connectionTestKey(storedSecretId, "")).isNull();
        assertThat(service.connectionTestKey(storedSecretId, "   ")).isNull();

        verify(secrets).decrypt(dsl, storedSecretId);
    }

    @Test
    void storedConnectionTestKeyIsRestrictedToThePersistedEndpoint() {
        var unchanged = new AiProviderUpdate(3L, "provider", "openai",
                "https://api.openai.com/v1", null, null, null, true, null);
        var changed = new AiProviderUpdate(3L, "provider", "openai",
                "https://attacker.example/v1", null, null, null, true, null);

        assertThat(AiProviderCatalogService.sameConnectionTarget("openai",
                "https://api.openai.com/v1", null, unchanged)).isTrue();
        assertThat(AiProviderCatalogService.sameConnectionTarget("openai",
                "https://api.openai.com/v1", null, changed)).isFalse();
    }

    @Test
    void rejectsProviderFamilyChangesWhileModelsRemainLinked() {
        DSLContext dsl = mock(DSLContext.class);
        ULong providerId = ULong.valueOf(7);
        when(dsl.fetchCount(eq(org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL),
                any(Condition.class))).thenReturn(1);

        assertThatThrownBy(() -> AiProviderCatalogService.requireCompatibleProviderFamily(
                dsl, providerId, "anthropic", "openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Remove the provider's models");
    }

    @Test
    void permitsProviderAliasesAndEmptyFamilyChanges() {
        DSLContext dsl = mock(DSLContext.class);
        ULong providerId = ULong.valueOf(7);

        AiProviderCatalogService.requireCompatibleProviderFamily(
                dsl, providerId, "azure-openai", "openai");
        verify(dsl, never()).fetchCount(
                eq(org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL),
                any(Condition.class));

        when(dsl.fetchCount(eq(org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_MODEL),
                any(Condition.class))).thenReturn(0);
        AiProviderCatalogService.requireCompatibleProviderFamily(
                dsl, providerId, "anthropic", "openai");
    }

}

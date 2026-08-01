package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_PROVIDER;

class AiProviderModelProfileServiceTest {

    @Test
    void authorizesAndMapsAnthropicAndOpenAiAliasesToProfiles() {
        assertThat(service("anthropic").modelProfiles(actor(), AiProviderId.from(1L)))
                .extracting(profile -> profile.modelKey())
                .containsExactly("claude-haiku-4_5", "claude-sonnet-4_5",
                        "claude-sonnet-4_6", "claude-sonnet-5", "claude-opus-4_5",
                        "claude-opus-4_6", "claude-opus-4_7", "claude-opus-4_8",
                        "claude-opus-5", "claude-fable-5", "claude-mythos-5");
        assertThat(service("openai").modelProfiles(actor(), AiProviderId.from(1L)))
                .extracting(profile -> profile.modelKey())
                .containsExactly("gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna",
                        "gpt-5_5", "gpt-5_5-pro", "gpt-5_4", "gpt-5_4-pro",
                        "gpt-5_4-mini", "gpt-5_4-nano");
        assertThat(service("azure-openai").modelProfiles(actor(), AiProviderId.from(1L)))
                .extracting(profile -> profile.modelKey())
                .containsExactly("gpt-5_6-sol", "gpt-5_6-terra", "gpt-5_6-luna",
                        "gpt-5_5", "gpt-5_5-pro", "gpt-5_4", "gpt-5_4-pro",
                        "gpt-5_4-mini", "gpt-5_4-nano");
    }

    @Test
    void requiresAdministratorAccessAndRejectsAMissingProvider() {
        AiAdminPolicyService authorization = mock(AiAdminPolicyService.class);
        ScoreUser actor = actor();
        AiProviderCatalogService service = service(null, authorization);

        assertThatThrownBy(() -> service.modelProfiles(actor, AiProviderId.from(99L)))
                .isInstanceOf(NotFoundException.class);
        verify(authorization).requireAdministrator(actor);
    }

    private AiProviderCatalogService service(String providerType) {
        return service(providerType, mock(AiAdminPolicyService.class));
    }

    private AiProviderCatalogService service(String providerType,
                                              AiAdminPolicyService authorization) {
        DSLContext dsl = DSL.using(new MockConnection(provider(providerType)), SQLDialect.MARIADB);
        return new AiProviderCatalogService(new RepositoryFactory(dsl),
                mock(ApplicationSecretService.class),
                authorization, new ObjectMapper(), mock(AiProviderConnectionTester.class));
    }

    private MockDataProvider provider(String providerType) {
        return context -> {
            DSLContext create = DSL.using(SQLDialect.MARIADB);
            Result<Record> result = create.newResult(AI_PROVIDER.fields());
            if (providerType != null) {
                Record record = create.newRecord(AI_PROVIDER);
                record.set(AI_PROVIDER.AI_PROVIDER_ID, ULong.valueOf(1));
                record.set(AI_PROVIDER.PROVIDER_NAME, "provider");
                record.set(AI_PROVIDER.PROVIDER_TYPE, providerType);
                record.set(AI_PROVIDER.ENABLED, (byte) 1);
                record.set(AI_PROVIDER.CATALOG_VERSION, ULong.valueOf(1));
                result.add(record);
            }
            return new MockResult[]{new MockResult(result.size(), result)};
        };
    }

    private ScoreUser actor() {
        return mock(ScoreUser.class);
    }
}

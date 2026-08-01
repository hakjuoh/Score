package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiProviderCatalogRepository;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.security.secret.AppSecretId;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;

import java.math.BigInteger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiProviderApiKeyServiceTest {

    private final RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
    private final ApplicationSecretService secrets = mock(ApplicationSecretService.class);
    private final AiAdminPolicyService authorization = mock(AiAdminPolicyService.class);
    private final AiProviderCatalogRepository repository = mock(AiProviderCatalogRepository.class);
    private final ScoreUser actor = mock(ScoreUser.class);
    private final UserId actorId = new UserId(BigInteger.valueOf(42));
    private final AiProviderId providerId = AiProviderId.from(7L);
    private final AppSecretId secretId = new AppSecretId(BigInteger.valueOf(11));
    private AiProviderCatalogService service;

    @BeforeEach
    void setUp() {
        var details = new AiProviderCatalogRepository.ConnectionDetails(
                "openai", "https://api.openai.com", null, secretId);
        when(repositoryFactory.aiProviderCatalogRepository(secrets))
                .thenReturn(repository);
        when(repository.findConnectionDetails(providerId)).thenReturn(Optional.of(details));
        when(actor.userId()).thenReturn(actorId);
        service = new AiProviderCatalogService(repositoryFactory, secrets, authorization,
                mock(AiProviderConnectionTester.class));
    }

    @Test
    void returnsAnExactLengthMaskAndClearsTheDecryptedBuffer() {
        char[] decrypted = "secret-value".toCharArray();
        when(repository.loadStoredApiKey(secretId)).thenReturn(decrypted);

        var masked = service.maskedApiKey(actor, providerId);

        assertThat(masked.value()).isEqualTo("•".repeat("secret-value".length()));
        assertThat(masked.revealed()).isFalse();
        assertThat(decrypted).containsOnly('\0');
    }

    @Test
    void revealsTheKeyAndClearsTheDecryptedBuffer() {
        char[] decrypted = "secret-value".toCharArray();
        when(repository.loadStoredApiKey(secretId)).thenReturn(decrypted);

        var revealed = service.revealApiKey(actor, providerId);

        assertThat(revealed.value()).isEqualTo("secret-value");
        assertThat(revealed.revealed()).isTrue();
        assertThat(revealed.toString()).doesNotContain("secret-value").contains("REDACTED");
        assertThat(decrypted).containsOnly('\0');
    }

    @Test
    void authorizationFailurePreventsCredentialLookup() {
        SecurityException denied = new SecurityException("denied");
        doThrow(denied).when(authorization).requireAdministrator(actor);

        assertThatThrownBy(() -> service.revealApiKey(actor, providerId)).isSameAs(denied);

        verify(repositoryFactory, never()).aiProviderCatalogRepository(secrets);
        verify(repository, never()).loadStoredApiKey(secretId);
    }

    @Test
    void absentCredentialReturnsEmptyValuesWithoutARevealAudit() {
        when(repository.loadStoredApiKey(secretId)).thenReturn(null);

        assertThat(service.maskedApiKey(actor, providerId).value()).isEmpty();
        assertThat(service.revealApiKey(actor, providerId).value()).isEmpty();

    }

    @Test
    void decryptionFailureIsPropagatedWithoutARevealAudit() {
        IllegalStateException failure = new IllegalStateException("cannot decrypt");
        when(repository.loadStoredApiKey(secretId)).thenThrow(failure);

        assertThatThrownBy(() -> service.revealApiKey(actor, providerId)).isSameAs(failure);

    }
}

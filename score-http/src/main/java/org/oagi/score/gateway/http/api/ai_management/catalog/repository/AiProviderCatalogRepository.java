package org.oagi.score.gateway.http.api.ai_management.catalog.repository;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderView;
import org.oagi.score.gateway.http.security.secret.AppSecretId;

import java.util.List;
import java.util.Optional;

public interface AiProviderCatalogRepository {

    List<AiProviderView> findAll();

    Optional<AiProviderView> findById(AiProviderId providerId);

    AiProviderView create(UserId actorUserId, AiProviderUpdate input);

    AiProviderView update(UserId actorUserId, AiProviderId providerId, AiProviderUpdate input);

    Optional<ConnectionDetails> findConnectionDetails(AiProviderId providerId);

    char[] loadConnectionTestKey(AppSecretId storedSecretId, String requestedKey);

    /**
     * Opens a stored credential for its immediate caller. The caller owns the returned buffer and
     * must clear it with {@link org.oagi.score.gateway.http.security.secret.ApplicationSecretService#clear(char[])}.
     */
    char[] loadStoredApiKey(AppSecretId storedSecretId);

    void recordApiKeyReveal(AiProviderId providerId, UserId actorUserId);

    String findConnectionTestModel(AiProviderId providerId);

    record ConnectionDetails(long catalogVersion, String providerType, String baseUrl,
                             String messagesUrl, AppSecretId secretId) {}
}

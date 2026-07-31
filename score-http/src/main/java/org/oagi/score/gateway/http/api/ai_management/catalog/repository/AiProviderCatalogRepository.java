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

    String findConnectionTestModel(AiProviderId providerId);

    record ConnectionDetails(long catalogVersion, String providerType, String baseUrl,
                             String messagesUrl, AppSecretId secretId) {}
}

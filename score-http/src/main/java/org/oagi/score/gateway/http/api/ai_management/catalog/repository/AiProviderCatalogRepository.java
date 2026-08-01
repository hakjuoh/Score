package org.oagi.score.gateway.http.api.ai_management.catalog.repository;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderView;
import org.oagi.score.gateway.http.security.secret.AppSecretId;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.PageResponse;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface AiProviderCatalogRepository {

    List<AiProviderView> findAll();

    PageResponse<AiProviderView> search(String name, String type, String endpoint,
                                        Boolean enabled, List<String> updaterLoginIdList,
                                        Instant updatedAfter, Instant updatedBefore,
                                        PageRequest pageRequest);

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

    String findConnectionTestModel(AiProviderId providerId);

    record ConnectionDetails(String providerType, String baseUrl,
                             String messagesUrl, AppSecretId secretId) {}
}

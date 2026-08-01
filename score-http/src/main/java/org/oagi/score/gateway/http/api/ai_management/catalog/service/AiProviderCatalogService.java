package org.oagi.score.gateway.http.api.ai_management.catalog.service;

import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderApiKeyView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderConnectionTestResult;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderView;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.AiModelProfileView;
import org.oagi.score.gateway.http.api.ai_management.catalog.repository.AiProviderCatalogRepository;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiAdminPolicyService;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.model.PageRequest;
import org.oagi.score.gateway.http.common.model.PageResponse;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Provider catalog management with encrypted credentials. Normal provider views omit credential
 * values; an administrator can explicitly reveal one through the dedicated reveal operation.
 */
@Service
public class AiProviderCatalogService {

    private final RepositoryFactory repositoryFactory;
    private final ApplicationSecretService secrets;
    private final AiAdminPolicyService authorization;
    private final AiProviderConnectionTester connectionTester;

    public AiProviderCatalogService(RepositoryFactory repositoryFactory,
                                    ApplicationSecretService secrets,
                                    AiAdminPolicyService authorization,
                                    AiProviderConnectionTester connectionTester) {
        this.repositoryFactory = repositoryFactory;
        this.secrets = secrets;
        this.authorization = authorization;
        this.connectionTester = connectionTester;
    }

    public List<AiProviderView> list(ScoreUser actor) {
        authorization.requireAdministrator(actor);
        return repository().findAll();
    }

    public PageResponse<AiProviderView> search(ScoreUser actor, String name, String type,
                                               String endpoint, Boolean enabled,
                                               List<String> updaterLoginIdList,
                                               Instant updatedAfter, Instant updatedBefore,
                                               PageRequest pageRequest) {
        authorization.requireAdministrator(actor);
        return repository().search(name, type, endpoint, enabled, updaterLoginIdList,
                updatedAfter, updatedBefore, pageRequest);
    }

    public AiProviderView get(ScoreUser actor, AiProviderId providerId) {
        authorization.requireAdministrator(actor);
        return repository().findById(providerId).orElseThrow(NotFoundException::new);
    }

    public AiProviderApiKeyView maskedApiKey(ScoreUser actor, AiProviderId providerId) {
        authorization.requireAdministrator(actor);
        AiProviderCatalogRepository repository = repository();
        AiProviderCatalogRepository.ConnectionDetails details = repository
                .findConnectionDetails(providerId).orElseThrow(NotFoundException::new);
        char[] apiKey = repository.loadStoredApiKey(details.secretId());
        try {
            String value = apiKey == null ? "" : "•".repeat(apiKey.length);
            return new AiProviderApiKeyView(value, false);
        } finally {
            ApplicationSecretService.clear(apiKey);
        }
    }

    public AiProviderApiKeyView revealApiKey(ScoreUser actor, AiProviderId providerId) {
        authorization.requireAdministrator(actor);
        AiProviderCatalogRepository repository = repository();
        AiProviderCatalogRepository.ConnectionDetails details = repository
                .findConnectionDetails(providerId).orElseThrow(NotFoundException::new);
        char[] apiKey = repository.loadStoredApiKey(details.secretId());
        try {
            if (apiKey == null) return new AiProviderApiKeyView("", true);
            return new AiProviderApiKeyView(new String(apiKey), true);
        } finally {
            ApplicationSecretService.clear(apiKey);
        }
    }

    public List<AiModelProfileView> modelProfiles(ScoreUser actor, AiProviderId providerId) {
        AiProviderView provider = get(actor, providerId);
        return AiModelProfileCatalog.modelsFor(provider.providerType()).stream()
                .map(AiModelProfileView::from).toList();
    }

    public AiProviderView create(ScoreUser actor, AiProviderUpdate input) {
        authorization.requireAdministrator(actor);
        validate(input);
        return repository().create(actor.userId(), input);
    }

    public AiProviderView update(ScoreUser actor, AiProviderId providerId,
                                 AiProviderUpdate input) {
        authorization.requireAdministrator(actor);
        validate(input);
        return repository().update(actor.userId(), providerId, input);
    }

    public AiProviderConnectionTestResult testConnection(ScoreUser actor, AiProviderId providerId,
                                                         AiProviderUpdate input) {
        authorization.requireAdministrator(actor);
        validate(input);
        AiProviderCatalogRepository repository = repository();
        AiProviderCatalogRepository.ConnectionDetails existing = repository
                .findConnectionDetails(providerId).orElseThrow(NotFoundException::new);
        if (input.apiKey() == null && !sameConnectionTarget(
                existing.providerType(), existing.baseUrl(), existing.messagesUrl(), input)) {
            throw new IllegalArgumentException(
                    "A replacement API key is required to test a changed provider endpoint.");
        }

        char[] apiKey;
        try {
            apiKey = repository.loadConnectionTestKey(existing.secretId(), input.apiKey());
        } catch (IllegalStateException exception) {
            return new AiProviderConnectionTestResult(false,
                    "The stored API key cannot be opened by this server.", null);
        }
        try {
            String modelName = "anthropic".equals(input.providerType().strip().toLowerCase())
                    ? repository.findConnectionTestModel(providerId) : null;
            return connectionTester.test(input, apiKey, modelName);
        } finally {
            ApplicationSecretService.clear(apiKey);
        }
    }

    static boolean sameConnectionTarget(String providerType, String baseUrl, String messagesUrl,
                                        AiProviderUpdate input) {
        return sameText(providerType, input.providerType())
                && sameText(baseUrl, input.baseUrl())
                && sameText(messagesUrl, input.messagesUrl());
    }

    private AiProviderCatalogRepository repository() {
        return repositoryFactory.aiProviderCatalogRepository(secrets);
    }

    private static boolean sameText(String stored, String requested) {
        return java.util.Objects.equals(nullable(stored), nullable(requested));
    }

    private static void validate(AiProviderUpdate input) {
        if (input == null || !StringUtils.hasText(input.providerName())
                || !StringUtils.hasText(input.providerType())) {
            throw new IllegalArgumentException("Provider name and type are required.");
        }
        if (input.enabled() && !StringUtils.hasText(input.baseUrl())
                && !StringUtils.hasText(input.messagesUrl())) {
            throw new IllegalArgumentException("An enabled provider requires an endpoint URL.");
        }
        String type = input.providerType().strip().toLowerCase();
        if (!java.util.Set.of("anthropic", "openai").contains(type)) {
            throw new IllegalArgumentException("Unsupported AI provider type: " + type);
        }
        validateEndpoint(input.baseUrl(), "base URL");
        validateEndpoint(input.messagesUrl(), "messages URL");
    }

    private static void validateEndpoint(String value, String label) {
        if (!StringUtils.hasText(value)) return;
        try {
            URI uri = URI.create(value.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException("Invalid AI provider " + label + ".");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid AI provider " + label + ".", exception);
        }
    }

    private static String nullable(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

}

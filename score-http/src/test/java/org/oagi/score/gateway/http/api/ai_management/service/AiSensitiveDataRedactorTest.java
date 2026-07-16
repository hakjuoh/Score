package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AiSensitiveDataRedactorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "authorization", "httpAuthorizationHeader", "apiKey", "access_token",
            "refreshToken", "authToken", "userAuth", "token", "passwordHash",
            "clientSecret", "credentialId", "cookieValue", "session", "userSessionId",
            "sessionToken"
    })
    void identifiesLogicalSecretKeys(String key) {
        assertThat(AiSensitiveDataRedactor.isSensitiveKey(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "authorId", "authorName", "coAuthor", "isAuthenticated", "authenticatedBy",
            "sessionCount", "sessionDurationMinutes", "tokenCount", "cookieName",
            "credentialType"
    })
    void preservesBenignKeysThatOnlyContainSensitiveSubstrings(String key) {
        assertThat(AiSensitiveDataRedactor.isSensitiveKey(key)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "authorId: 42", "authorName=Alice", "isAuthenticated: true",
            "sessionCount: 3", "sessionDurationMinutes=45"
    })
    void preservesBenignFreeTextLabels(String text) {
        assertThat(AiSensitiveDataRedactor.redactText(text)).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "auth: bearer-value", "session: session-value", "token=plain-token",
            "cookie: browser-cookie", "credential=credential-value"
    })
    void redactsExactSensitiveFreeTextLabels(String text) {
        assertThat(AiSensitiveDataRedactor.redactText(text))
                .contains("[REDACTED]")
                .doesNotContain("-value", "plain-token", "browser-cookie");
    }
}

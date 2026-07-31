package org.oagi.score.gateway.http.api.ai_management.catalog.model;

/** Safe, non-persistent result of checking a provider endpoint and credential. */
public record AiProviderConnectionTestResult(boolean successful, String message,
                                             Integer statusCode) {
}

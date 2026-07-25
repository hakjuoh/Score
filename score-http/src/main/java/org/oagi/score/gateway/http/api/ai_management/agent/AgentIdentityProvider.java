package org.oagi.score.gateway.http.api.ai_management.agent;

/**
 * Supplies the stable identity of the configured root Agent.
 *
 * <p>This narrow seam is intentionally independent of the provider adapter. It
 * lets application services record the root Agent without depending on the
 * component that happens to execute model calls.</p>
 */
@FunctionalInterface
public interface AgentIdentityProvider {

    String rootAgentId();
}

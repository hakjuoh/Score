package org.oagi.score.gateway.http.api.ai_management.conversation;

/** Optional trigger policy; hard context limits remain in the caller regardless. */
@FunctionalInterface
public interface CompactionPolicy {
    boolean shouldCompact(long projectedInputTokens);
}

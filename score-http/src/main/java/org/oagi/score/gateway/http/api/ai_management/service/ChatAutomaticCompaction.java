package org.oagi.score.gateway.http.api.ai_management.service;

/** Immutable automatic-compaction state carried from execution into commit. */
record ChatAutomaticCompaction(String summary, long beforeTokens, long afterTokens) {

    static ChatAutomaticCompaction none() {
        return new ChatAutomaticCompaction(null, 0L, 0L);
    }

    boolean occurred() {
        return summary != null;
    }
}

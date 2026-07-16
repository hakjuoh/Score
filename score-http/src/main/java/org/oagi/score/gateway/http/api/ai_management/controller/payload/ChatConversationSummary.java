package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import java.time.Instant;

public record ChatConversationSummary(String conversationId, String title, int visibleMessageCount,
                                      boolean compacted, Instant createdAt, Instant updatedAt) {}

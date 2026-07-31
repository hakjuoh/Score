package org.oagi.score.gateway.http.api.ai_management.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** Result of selecting and executing a workflow or compaction path for one turn. */
record ChatTurnExecution(ChatTurnOutput output, List<Message> history,
                         ChatAutomaticCompaction automaticCompaction) {

    ChatTurnExecution {
        history = history != null ? List.copyOf(history) : List.of();
        automaticCompaction = automaticCompaction != null
                ? automaticCompaction : ChatAutomaticCompaction.none();
    }
}

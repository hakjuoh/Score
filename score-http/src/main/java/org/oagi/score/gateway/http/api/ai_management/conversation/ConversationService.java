package org.oagi.score.gateway.http.api.ai_management.conversation;

import java.util.Objects;

/** Loads immutable application-owned conversation snapshots. */
public final class ConversationService {

    private final ConversationStores.State state;
    private final ConversationStores.History history;
    private final ConversationStores.Transcript transcript;
    private final ConversationStores.Memory memory;

    public ConversationService(ConversationStores.State state, ConversationStores.History history,
                               ConversationStores.Transcript transcript,
                               ConversationStores.Memory memory) {
        this.state = Objects.requireNonNull(state); this.history = Objects.requireNonNull(history);
        this.transcript = Objects.requireNonNull(transcript); this.memory = Objects.requireNonNull(memory);
    }

    public ConversationSnapshot snapshot(String conversationId, long generation) {
        return new ConversationSnapshot(conversationId, generation, state.settings(conversationId),
                history.load(conversationId), transcript.load(conversationId), memory.load(conversationId));
    }
}

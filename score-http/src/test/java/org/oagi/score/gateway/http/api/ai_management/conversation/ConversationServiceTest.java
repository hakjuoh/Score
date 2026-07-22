package org.oagi.score.gateway.http.api.ai_management.conversation;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationServiceTest {

    @Test
    void snapshotLoadsEachCanonicalPortOnce() {
        AtomicInteger historyLoads = new AtomicInteger();
        AtomicInteger transcriptLoads = new AtomicInteger();
        AtomicInteger memoryLoads = new AtomicInteger();
        ConversationStores.State state = new ConversationStores.State() {
            @Override public ConversationSnapshot.ConversationSettings settings(String id) {
                return new ConversationSnapshot.ConversationSettings("model", "low", null, "root-agent");
            }
            @Override public void saveSettings(String id, ConversationSnapshot.ConversationSettings settings) { }
        };
        ConversationStores.History history = new ConversationStores.History() {
            @Override public List<AiMessage> load(String id) {
                historyLoads.incrementAndGet(); return List.of(new AiMessage.User("history"));
            }
            @Override public void append(String id, List<AiMessage> messages) { }
            @Override public void replace(String id, List<AiMessage> messages) { }
        };
        ConversationStores.Transcript transcript = new ConversationStores.Transcript() {
            @Override public List<AiMessage> load(String id) {
                transcriptLoads.incrementAndGet(); return List.of();
            }
            @Override public void append(String id, List<AiMessage> messages) { }
        };
        ConversationStores.Memory memory = new ConversationStores.Memory() {
            @Override public Map<String, Object> load(String id) {
                memoryLoads.incrementAndGet(); return Map.of();
            }
            @Override public void replace(String id, Map<String, Object> value) { }
        };
        ConversationService service = new ConversationService(state, history, transcript, memory);

        ConversationSnapshot snapshot = service.snapshot("conversation", 7);

        assertThat(snapshot.generation()).isEqualTo(7);
        assertThat(historyLoads).hasValue(1);
        assertThat(transcriptLoads).hasValue(1);
        assertThat(memoryLoads).hasValue(1);
    }
}

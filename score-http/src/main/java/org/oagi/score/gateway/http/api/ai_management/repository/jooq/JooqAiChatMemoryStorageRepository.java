package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatMemoryEntry;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatMemoryStorageRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.JooqBaseRepository;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.common.repository.jooq.entity.tables.records.AiChatMemoryRecord;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_MEMORY;

/**
 * jOOQ persistence for bounded Spring AI model-memory rows.
 */
public class JooqAiChatMemoryStorageRepository extends JooqBaseRepository
        implements AiChatMemoryStorageRepository {

    private final AiChatJsonSerializer serializer;

    /**
     * Creates the model-memory storage repository.
     *
     * @param dslContext context used to query and update model-memory rows
     * @param repositoryFactory factory for related database repositories
     * @param serializer serializer for JSON-backed AI chat data
     */
    public JooqAiChatMemoryStorageRepository(DSLContext dslContext, ScoreUser requester,
                                             RepositoryFactory repositoryFactory,
                                             AiChatJsonSerializer serializer) {
        super(dslContext, Objects.requireNonNull(requester, "requester must not be null"),
                repositoryFactory);
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
    }

    @Override
    public List<String> findConversationIds() {
        return dslContext().selectDistinct(AI_CHAT_CONVERSATION.GUID)
                .from(AI_CHAT_MEMORY)
                .join(AI_CHAT_CONVERSATION).on(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_MEMORY.AI_CHAT_CONVERSATION_ID))
                .where(AI_CHAT_CONVERSATION.APP_USER_ID.eq(valueOf(userId())))
                .orderBy(AI_CHAT_CONVERSATION.GUID)
                .limit(1000)
                .fetch(AI_CHAT_CONVERSATION.GUID);
    }

    @Override
    public List<AiChatMemoryEntry> findByConversationId(String conversationId) {
        requireConversationId(conversationId);
        return dslContext().select(AI_CHAT_MEMORY.MESSAGE_TYPE, AI_CHAT_MEMORY.CONTENT,
                        AI_CHAT_MEMORY.METADATA_JSON)
                .from(AI_CHAT_MEMORY)
                .join(AI_CHAT_CONVERSATION).on(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID
                        .eq(AI_CHAT_MEMORY.AI_CHAT_CONVERSATION_ID))
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(valueOf(userId()))))
                .orderBy(AI_CHAT_MEMORY.MEMORY_SEQUENCE)
                .fetch(record -> new AiChatMemoryEntry(
                        record.get(AI_CHAT_MEMORY.MESSAGE_TYPE),
                        record.get(AI_CHAT_MEMORY.CONTENT),
                        serializer.deserializeMap(record.get(AI_CHAT_MEMORY.METADATA_JSON))));
    }

    @Override
    public void saveAll(String conversationId, List<AiChatMemoryEntry> entries) {
        requireConversationId(conversationId);
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("entries must not contain null elements");
        }
        AiChatConversationId internalConversationId = internalConversationId(conversationId);
        dslContext().deleteFrom(AI_CHAT_MEMORY)
                .where(AI_CHAT_MEMORY.AI_CHAT_CONVERSATION_ID.eq(valueOf(internalConversationId)))
                .execute();
        Instant now = Instant.now();
        for (int index = 0; index < entries.size(); index++) {
            AiChatMemoryEntry entry = entries.get(index);
            AiChatMemoryRecord record = new AiChatMemoryRecord();
            record.setAiChatConversationId(valueOf(internalConversationId));
            record.setMemorySequence((long) index);
            record.setMessageType(entry.messageType());
            record.setContent(Objects.requireNonNullElse(entry.content(), ""));
            record.setMetadataJson(serializer.serialize(entry.metadata()));
            record.setCreatedAt(LocalDateTime.ofInstant(now, ZoneId.systemDefault()));
            dslContext().insertInto(AI_CHAT_MEMORY).set(record).execute();
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        requireConversationId(conversationId);
        dslContext().select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(valueOf(userId()))))
                .fetchOptional(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .map(id -> new AiChatConversationId(id.toBigInteger()))
                .ifPresent(internalConversationId -> dslContext().deleteFrom(AI_CHAT_MEMORY)
                        .where(AI_CHAT_MEMORY.AI_CHAT_CONVERSATION_ID
                                .eq(valueOf(internalConversationId)))
                        .execute());
    }

    private AiChatConversationId internalConversationId(String conversationId) {
        return dslContext().select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationId)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(valueOf(userId()))))
                .fetchOptional(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .map(id -> new AiChatConversationId(id.toBigInteger()))
                .orElseThrow(() -> new IllegalArgumentException("AI conversation does not exist."));
    }

    private void requireConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId must not be empty");
        }
    }

    private UserId userId() {
        return requester().userId();
    }

}

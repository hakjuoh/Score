package org.oagi.score.gateway.http.api.ai_management.tool.file;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.types.ULong;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.tables.AiChatConversation.AI_CHAT_CONVERSATION;

@Repository
public class AiFileRepository {

    private static final Table<?> FILE = table(name("ai_chat_file"));
    private static final Field<ULong> ID = fileField("ai_chat_file_id", ULong.class);
    private static final Field<String> GUID = fileField("guid", String.class);
    private static final Field<ULong> CONVERSATION_ID = fileField("ai_chat_conversation_id", ULong.class);
    private static final Field<String> REQUEST_ID = fileField("request_id", String.class);
    private static final Field<String> FORMAT = fileField("format", String.class);
    private static final Field<String> FILENAME = fileField("filename", String.class);
    private static final Field<String> MEDIA_TYPE = fileField("media_type", String.class);
    private static final Field<ULong> BYTE_SIZE = fileField("byte_size", ULong.class);
    private static final Field<String> SHA256 = fileField("sha256", String.class);
    private static final Field<String> STORAGE_PROVIDER = fileField("storage_provider", String.class);
    private static final Field<String> STORAGE_LOCATION = fileField("storage_location", String.class);
    private static final Field<LocalDateTime> CREATION_TIMESTAMP =
            fileField("creation_timestamp", LocalDateTime.class);
    private static final Field<LocalDateTime> EXPIRATION_TIMESTAMP =
            fileField("expiration_timestamp", LocalDateTime.class);

    private final DSLContext dsl;

    public AiFileRepository(DSLContext dsl) { this.dsl = dsl; }

    public AiFileRecord insert(ScoreUser requester, AiFileRecord value) {
        ULong conversationId = ownedConversationId(requester, value.conversationId());
        try {
            dsl.insertInto(FILE)
                    .columns(GUID, CONVERSATION_ID, REQUEST_ID, FORMAT, FILENAME, MEDIA_TYPE,
                            BYTE_SIZE, SHA256, STORAGE_PROVIDER, STORAGE_LOCATION,
                            CREATION_TIMESTAMP, EXPIRATION_TIMESTAMP)
                    .values(value.fileId(), conversationId, value.requestId(), value.format(),
                            value.filename(), value.mediaType(), ULong.valueOf(value.size()), value.sha256(),
                            value.storageProvider(), value.storageLocation(), local(value.createdAt()),
                            local(value.expiresAt()))
                    .execute();
            return value;
        } catch (DataAccessException duplicate) {
            return findDuplicate(requester, value.conversationId(), value.requestId(),
                    value.filename(), value.sha256()).orElseThrow(() -> duplicate);
        }
    }

    public List<AiFileRecord> findByRequest(ScoreUser requester, String conversationGuid,
                                                String requestId) {
        ULong conversationId = ownedConversationId(requester, conversationGuid);
        return selectRecords().where(CONVERSATION_ID.eq(conversationId).and(REQUEST_ID.eq(requestId)))
                .orderBy(CREATION_TIMESTAMP, ID).fetch(record -> map(record, conversationGuid));
    }

    public Optional<AiFileRecord> findOwned(ScoreUser requester, String conversationGuid,
                                                String fileGuid) {
        ULong conversationId = ownedConversationId(requester, conversationGuid);
        return selectRecords().where(CONVERSATION_ID.eq(conversationId).and(GUID.eq(fileGuid)))
                .fetchOptional(record -> map(record, conversationGuid));
    }

    public List<AiFileRecord> findByConversation(ScoreUser requester, String conversationGuid) {
        ULong conversationId = ownedConversationId(requester, conversationGuid);
        return findByConversationTree(conversationId);
    }

    public List<AiFileRecord> findByConversationTreeForUpdate(String conversationGuid) {
        ULong conversationId = dsl.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationGuid)
                        .and(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID.isNull()))
                .forUpdate()
                .fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        return conversationId != null ? findByConversationTree(conversationId) : List.of();
    }

    public List<AiFileRecord> findExpired(Instant cutoff, int limit) {
        return findExpired(cutoff, limit, Set.of());
    }

    public List<AiFileRecord> findExpired(Instant cutoff, int limit,
                                              Set<String> excludedFileIds) {
        org.jooq.Condition condition = EXPIRATION_TIMESTAMP.le(local(cutoff));
        if (excludedFileIds != null && !excludedFileIds.isEmpty()) {
            condition = condition.and(GUID.notIn(excludedFileIds));
        }
        return selectRecords()
                .join(AI_CHAT_CONVERSATION).on(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(CONVERSATION_ID))
                .where(condition)
                .orderBy(EXPIRATION_TIMESTAMP, ID).limit(Math.max(1, limit))
                .fetch(record -> map(record, record.get(AI_CHAT_CONVERSATION.GUID)));
    }

    public void delete(String fileId) {
        dsl.deleteFrom(FILE).where(GUID.eq(fileId)).execute();
    }

    private Optional<AiFileRecord> findDuplicate(ScoreUser requester, String conversationGuid,
                                                      String requestId, String filename, String sha256) {
        ULong conversationId = ownedConversationId(requester, conversationGuid);
        return selectRecords().where(CONVERSATION_ID.eq(conversationId)
                        .and(REQUEST_ID.eq(requestId)).and(FILENAME.eq(filename)).and(SHA256.eq(sha256)))
                .fetchOptional(record -> map(record, conversationGuid));
    }

    private List<AiFileRecord> findByConversationTree(ULong rootId) {
        Map<ULong, String> conversations = new LinkedHashMap<>();
        List<ULong> frontier = List.of(rootId);
        while (!frontier.isEmpty()) {
            List<org.jooq.Record2<ULong, String>> level = dsl
                    .select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID, AI_CHAT_CONVERSATION.GUID)
                    .from(AI_CHAT_CONVERSATION)
                    .where(conversations.isEmpty()
                            ? AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.in(frontier)
                            : AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID.in(frontier))
                    .orderBy(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                    .forUpdate()
                    .fetch();
            List<ULong> next = new ArrayList<>(level.size());
            for (org.jooq.Record2<ULong, String> conversation : level) {
                ULong id = conversation.value1();
                conversations.put(id, conversation.value2());
                next.add(id);
            }
            frontier = next;
        }
        if (conversations.isEmpty()) return List.of();
        return selectRecords().where(CONVERSATION_ID.in(conversations.keySet()))
                .orderBy(CREATION_TIMESTAMP, ID)
                .fetch(record -> map(record, conversations.get(record.get(CONVERSATION_ID))));
    }

    private org.jooq.SelectJoinStep<? extends Record> selectRecords() {
        return dsl.select(ID, GUID, CONVERSATION_ID, REQUEST_ID, FORMAT, FILENAME, MEDIA_TYPE,
                BYTE_SIZE, SHA256, STORAGE_PROVIDER, STORAGE_LOCATION,
                CREATION_TIMESTAMP, EXPIRATION_TIMESTAMP)
                .from(FILE);
    }

    private AiFileRecord map(Record record, String conversationGuid) {
        ULong size = record.get(BYTE_SIZE);
        return new AiFileRecord(record.get(GUID), conversationGuid, record.get(REQUEST_ID),
                record.get(FORMAT), record.get(FILENAME), record.get(MEDIA_TYPE),
                size != null ? size.longValue() : 0L, record.get(SHA256),
                record.get(STORAGE_PROVIDER), record.get(STORAGE_LOCATION),
                instant(record.get(CREATION_TIMESTAMP)),
                instant(record.get(EXPIRATION_TIMESTAMP)));
    }

    private ULong ownedConversationId(ScoreUser requester, String conversationGuid) {
        if (requester == null || requester.userId() == null) throw new AccessDeniedException("Authentication is required.");
        ULong id = dsl.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationGuid)
                        .and(AI_CHAT_CONVERSATION.APP_USER_ID.eq(ULong.valueOf(requester.userId().value()))))
                .fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        if (id == null) throw new AccessDeniedException("AI conversation does not exist or is not owned by the signed-in user.");
        return id;
    }

    private LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Instant instant(LocalDateTime value) {
        return value != null ? value.atZone(ZoneId.systemDefault()).toInstant() : Instant.EPOCH;
    }

    private static <T> Field<T> fileField(String column, Class<T> type) {
        return field(name("ai_chat_file", column), type);
    }
}

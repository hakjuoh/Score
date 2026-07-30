package org.oagi.score.gateway.http.api.ai_management.artifact;

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
public class AiArtifactRepository {

    private static final Table<?> ARTIFACT = table(name("ai_chat_artifact"));
    private static final Field<ULong> ID = artifactField("ai_chat_artifact_id", ULong.class);
    private static final Field<String> GUID = artifactField("guid", String.class);
    private static final Field<ULong> CONVERSATION_ID = artifactField("ai_chat_conversation_id", ULong.class);
    private static final Field<String> REQUEST_ID = artifactField("request_id", String.class);
    private static final Field<String> FORMAT = artifactField("format", String.class);
    private static final Field<String> FILENAME = artifactField("filename", String.class);
    private static final Field<String> MEDIA_TYPE = artifactField("media_type", String.class);
    private static final Field<ULong> BYTE_SIZE = artifactField("byte_size", ULong.class);
    private static final Field<String> SHA256 = artifactField("sha256", String.class);
    private static final Field<String> STORAGE_PROVIDER = artifactField("storage_provider", String.class);
    private static final Field<String> STORAGE_LOCATION = artifactField("storage_location", String.class);
    private static final Field<LocalDateTime> CREATED_AT = artifactField("created_at", LocalDateTime.class);
    private static final Field<LocalDateTime> EXPIRES_AT = artifactField("expires_at", LocalDateTime.class);

    private final DSLContext dsl;

    public AiArtifactRepository(DSLContext dsl) { this.dsl = dsl; }

    public AiArtifactRecord insert(ScoreUser requester, AiArtifactRecord value) {
        ULong conversationId = ownedConversationId(requester, value.conversationId());
        try {
            dsl.insertInto(ARTIFACT)
                    .columns(GUID, CONVERSATION_ID, REQUEST_ID, FORMAT, FILENAME, MEDIA_TYPE,
                            BYTE_SIZE, SHA256, STORAGE_PROVIDER, STORAGE_LOCATION, CREATED_AT, EXPIRES_AT)
                    .values(value.artifactId(), conversationId, value.requestId(), value.format(),
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

    public List<AiArtifactRecord> findByRequest(ScoreUser requester, String conversationGuid,
                                                String requestId) {
        ULong conversationId = ownedConversationId(requester, conversationGuid);
        return selectRecords().where(CONVERSATION_ID.eq(conversationId).and(REQUEST_ID.eq(requestId)))
                .orderBy(CREATED_AT, ID).fetch(record -> map(record, conversationGuid));
    }

    public Optional<AiArtifactRecord> findOwned(ScoreUser requester, String conversationGuid,
                                                String artifactGuid) {
        ULong conversationId = ownedConversationId(requester, conversationGuid);
        return selectRecords().where(CONVERSATION_ID.eq(conversationId).and(GUID.eq(artifactGuid)))
                .fetchOptional(record -> map(record, conversationGuid));
    }

    public List<AiArtifactRecord> findByConversation(ScoreUser requester, String conversationGuid) {
        ULong conversationId = ownedConversationId(requester, conversationGuid);
        return findByConversationTree(conversationId);
    }

    public List<AiArtifactRecord> findByConversationTreeForUpdate(String conversationGuid) {
        ULong conversationId = dsl.select(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID)
                .from(AI_CHAT_CONVERSATION)
                .where(AI_CHAT_CONVERSATION.GUID.eq(conversationGuid)
                        .and(AI_CHAT_CONVERSATION.PARENT_AI_CHAT_CONVERSATION_ID.isNull()))
                .forUpdate()
                .fetchOne(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
        return conversationId != null ? findByConversationTree(conversationId) : List.of();
    }

    public List<AiArtifactRecord> findExpired(Instant cutoff, int limit) {
        return findExpired(cutoff, limit, Set.of());
    }

    public List<AiArtifactRecord> findExpired(Instant cutoff, int limit,
                                              Set<String> excludedArtifactIds) {
        org.jooq.Condition condition = EXPIRES_AT.le(local(cutoff));
        if (excludedArtifactIds != null && !excludedArtifactIds.isEmpty()) {
            condition = condition.and(GUID.notIn(excludedArtifactIds));
        }
        return selectRecords()
                .join(AI_CHAT_CONVERSATION).on(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID.eq(CONVERSATION_ID))
                .where(condition)
                .orderBy(EXPIRES_AT, ID).limit(Math.max(1, limit))
                .fetch(record -> map(record, record.get(AI_CHAT_CONVERSATION.GUID)));
    }

    public void delete(String artifactId) {
        dsl.deleteFrom(ARTIFACT).where(GUID.eq(artifactId)).execute();
    }

    private Optional<AiArtifactRecord> findDuplicate(ScoreUser requester, String conversationGuid,
                                                      String requestId, String filename, String sha256) {
        ULong conversationId = ownedConversationId(requester, conversationGuid);
        return selectRecords().where(CONVERSATION_ID.eq(conversationId)
                        .and(REQUEST_ID.eq(requestId)).and(FILENAME.eq(filename)).and(SHA256.eq(sha256)))
                .fetchOptional(record -> map(record, conversationGuid));
    }

    private List<AiArtifactRecord> findByConversationTree(ULong rootId) {
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
                .orderBy(CREATED_AT, ID)
                .fetch(record -> map(record, conversations.get(record.get(CONVERSATION_ID))));
    }

    private org.jooq.SelectJoinStep<? extends Record> selectRecords() {
        return dsl.select(ID, GUID, CONVERSATION_ID, REQUEST_ID, FORMAT, FILENAME, MEDIA_TYPE,
                BYTE_SIZE, SHA256, STORAGE_PROVIDER, STORAGE_LOCATION, CREATED_AT, EXPIRES_AT)
                .from(ARTIFACT);
    }

    private AiArtifactRecord map(Record record, String conversationGuid) {
        ULong size = record.get(BYTE_SIZE);
        return new AiArtifactRecord(record.get(GUID), conversationGuid, record.get(REQUEST_ID),
                record.get(FORMAT), record.get(FILENAME), record.get(MEDIA_TYPE),
                size != null ? size.longValue() : 0L, record.get(SHA256),
                record.get(STORAGE_PROVIDER), record.get(STORAGE_LOCATION),
                instant(record.get(CREATED_AT)), instant(record.get(EXPIRES_AT)));
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

    private static <T> Field<T> artifactField(String column, Class<T> type) {
        return field(name("ai_chat_artifact", column), type);
    }
}

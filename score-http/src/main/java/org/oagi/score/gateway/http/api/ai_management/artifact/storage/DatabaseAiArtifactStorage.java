package org.oagi.score.gateway.http.api.ai_management.artifact.storage;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

@Component
public final class DatabaseAiArtifactStorage implements AiArtifactStorage {

    private static final Table<?> OBJECT = table(name("ai_chat_artifact_object"));
    private static final Field<String> LOCATION = field(name("storage_location"), String.class);
    private static final Field<byte[]> CONTENT = field(name("content"), byte[].class);
    private static final Field<LocalDateTime> CREATED_AT = field(name("created_at"), LocalDateTime.class);
    private final DSLContext dsl;

    public DatabaseAiArtifactStorage(DSLContext dsl) { this.dsl = dsl; }

    @Override public String id() { return "db"; }

    @Override
    public String store(String objectKey, String filename, String mediaType, byte[] content) {
        dsl.insertInto(OBJECT).columns(LOCATION, CONTENT, CREATED_AT)
                .values(objectKey, content, LocalDateTime.now()).execute();
        return objectKey;
    }

    @Override
    public byte[] load(String location) {
        byte[] content = dsl.select(CONTENT).from(OBJECT).where(LOCATION.eq(location))
                .fetchOne(CONTENT);
        if (content == null) throw new IllegalArgumentException("Artifact object does not exist.");
        return content;
    }

    @Override
    public void delete(String location) {
        dsl.deleteFrom(OBJECT).where(LOCATION.eq(location)).execute();
    }
}

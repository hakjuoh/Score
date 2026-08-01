package org.oagi.score.gateway.http.api.ai_management.tool.file.storage;

import org.h2.jdbcx.JdbcDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.tool.file.storage.DatabaseAiFileStorage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseAiFileStorageTest {

    @Test
    void storesLoadsAndDeletesBinaryObjects() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE ai_chat_file_object (
                    storage_location VARCHAR(512) PRIMARY KEY,
                    content BLOB NOT NULL,
                    creation_timestamp TIMESTAMP(6) NOT NULL)
                """);
        DSLContext dsl = DSL.using(dataSource, SQLDialect.H2,
                new Settings().withRenderSchema(false));
        DatabaseAiFileStorage storage = new DatabaseAiFileStorage(dsl);
        byte[] content = "file".getBytes(StandardCharsets.UTF_8);

        String location = storage.store("conversation/request/file", "file.md",
                "text/markdown", content);

        assertThat(location).isEqualTo("conversation/request/file");
        assertThat(storage.load(location)).isEqualTo(content);
        storage.delete(location);
        assertThatThrownBy(() -> storage.load(location))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }
}

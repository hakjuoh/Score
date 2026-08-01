package org.oagi.score.gateway.http.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3_6_0AiAuditColumnsSchemaTest {
    private static final String MIGRATION = "/db/migration/V3_6_0__upgrade_from_3_5_2.sql";
    private static final List<String> AUDITED_TABLES =
            List.of("app_secret", "ai_provider", "ai_model", "ai_user_policy");
    private static final List<String> MUTABLE_TIMESTAMP_TABLES =
            List.of("app_secret", "ai_provider", "ai_model", "ai_user_policy",
                    "ai_chat_conversation", "ai_token_usage_period",
                    "ai_token_request_usage");

    @Test
    void mutableAiConfigurationTablesUseRequiredConventionalAuditColumns() throws IOException {
        String migration = readMigration();

        for (String table : AUDITED_TABLES) {
            assertAuditContract(tableDefinition(migration, table), table);
        }
    }

    @Test
    void canonicalDdlTemplatesMatchTheMigrationAuditContract() throws IOException {
        for (String table : AUDITED_TABLES) {
            assertAuditContract(readResource("/schemas/" + table + ".ddl"), table);
        }
    }

    @Test
    void everyV360DatetimeColumnUsesTheTimestampNamingConvention() throws IOException {
        String migration = readMigration();
        Matcher matcher = Pattern.compile("`([^`]+)`\\s+datetime\\(6\\)",
                Pattern.CASE_INSENSITIVE).matcher(migration);
        int timestampColumnCount = 0;
        while (matcher.find()) {
            timestampColumnCount++;
            assertTrue(matcher.group(1).endsWith("_timestamp"),
                    matcher.group(1) + " must use the *_timestamp convention");
        }
        assertTrue(timestampColumnCount > 0, "The migration must declare timestamp columns");
    }

    @Test
    void mutableTablesRequireCreationAndLastUpdateTimestamps() throws IOException {
        String migration = readMigration();
        for (String table : MUTABLE_TIMESTAMP_TABLES) {
            String definition = tableDefinition(migration, table);
            assertRequiredColumn(definition, table, "creation_timestamp", "datetime\\(6\\)");
            assertRequiredColumn(definition, table, "last_update_timestamp", "datetime\\(6\\)");
        }
    }

    private static void assertAuditContract(String definition, String table) {
        assertRequiredColumn(definition, table, "created_by", "bigint\\(20\\) unsigned");
        assertRequiredColumn(definition, table, "last_updated_by", "bigint\\(20\\) unsigned");
        assertRequiredColumn(definition, table, "creation_timestamp", "datetime\\(6\\)");
        assertRequiredColumn(definition, table, "last_update_timestamp", "datetime\\(6\\)");
        assertAuditForeignKey(definition, table, "created_by");
        assertAuditForeignKey(definition, table, "last_updated_by");
        assertFalse(definition.contains("`created_at`"),
                table + " must not use created_at for configuration audit data");
        assertFalse(definition.contains("`last_updated_at`"),
                table + " must not use last_updated_at");
        assertFalse(definition.contains("ON DELETE SET NULL"),
                table + " must not null a required audit user reference");
    }

    private static void assertRequiredColumn(
            String definition, String table, String column, String sqlType) {
        assertTrue(Pattern.compile("`" + column + "`\\s+" + sqlType + "\\s+NOT NULL")
                        .matcher(definition).find(),
                table + " must require " + column);
    }

    private static void assertAuditForeignKey(
            String definition, String table, String column) {
        String constraint = "CONSTRAINT\\s+`" + table + "_" + column
                + "_fk`\\s+FOREIGN KEY\\s*\\(`" + column
                + "`\\)\\s+REFERENCES\\s+`app_user`\\s*\\(`app_user_id`\\)";
        assertTrue(Pattern.compile(constraint, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                        .matcher(definition).find(),
                table + " must constrain " + column + " to app_user.app_user_id");
    }

    private static String tableDefinition(String migration, String table) {
        String marker = "CREATE TABLE `" + table + "`";
        int start = migration.indexOf(marker);
        assertTrue(start >= 0, "Missing table " + table);
        int tableOptions = migration.indexOf("\n) ENGINE", start);
        assertTrue(tableOptions > start, "Missing table options for " + table);
        int end = migration.indexOf(";", tableOptions);
        assertTrue(end > start, "Unterminated table " + table);
        return migration.substring(start, end + 1);
    }

    private static String readMigration() throws IOException {
        return readResource(MIGRATION);
    }

    private static String readResource(String path) throws IOException {
        try (var input = V3_6_0AiAuditColumnsSchemaTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "Schema resource not found on the classpath: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

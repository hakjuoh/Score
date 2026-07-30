package org.oagi.score.gateway.http.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_CHANGE_CONFIRMATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_FILE;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_FILE_OBJECT;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_STEP;

class V3_6_0AiChatSettingsSchemaTest {

    private static final String MIGRATION = "/db/migration/V3_6_0__upgrade_from_3_5_2.sql";
    private static final Pattern MESSAGE_KIND_DEFINITION = Pattern.compile(
            "`message_kind`\\s+varchar\\((\\d+)\\)\\s+NOT NULL",
            Pattern.CASE_INSENSITIVE);

    @Test
    void keepsSettingsValidationInTheApplication() throws IOException {
        String migration = readMigration();
        String conversationTable = migration.substring(
                migration.indexOf("CREATE TABLE `ai_chat_conversation`"),
                migration.indexOf("CREATE TABLE `ai_chat_memory`"));

        assertFalse(conversationTable.contains("`model_name`"));
        assertFalse(conversationTable.contains("`reasoning_effort`"));
        assertFalse(migration.contains("CONSTRAINT `ai_chat_step_settings_change_ck`"));
        assertTrue(migration.contains("`ai_chat_step_settings_idx`"
                + " (`ai_chat_conversation_id`, `message_kind`, `step_sequence`)"));
        assertTrue(migration.contains("CREATE TABLE `ai_chat_change_confirmation`"));
        assertTrue(migration.contains("UNIQUE KEY `ai_chat_change_confirmation_grant_uk`"));
        assertTrue(migration.contains("CREATE TABLE `ai_chat_file`"));
        assertTrue(migration.contains("CREATE TABLE `ai_chat_file_object`"));
        assertFalse(migration.contains("ai_chat_mutation_confirmation"));
        assertFalse(migration.contains("ai_chat_artifact"));
        assertTrue(migration.contains("Expected confirmation states are REQUESTED, APPROVED, DENIED, CONSUMED, and EXPIRED"));
        assertTrue(conversationTable.contains("`compacted`"));
        assertEquals("ai_chat_change_confirmation", AI_CHAT_CHANGE_CONFIRMATION.getName());
        assertEquals("ai_chat_file", AI_CHAT_FILE.getName());
        assertEquals("ai_chat_file_object", AI_CHAT_FILE_OBJECT.getName());
    }

    @Test
    void keepsMessageKindLengthAlignedWithGeneratedJooqMetadata() throws IOException {
        String migration = readMigration();
        String stepTable = migration.substring(
                migration.indexOf("CREATE TABLE `ai_chat_step`"),
                migration.indexOf("CREATE TABLE `ai_chat_change_confirmation`"));
        Matcher definition = MESSAGE_KIND_DEFINITION.matcher(stepTable);

        assertTrue(definition.find(), "ai_chat_step.message_kind definition not found");
        assertEquals(AI_CHAT_STEP.MESSAGE_KIND.getDataType().length(),
                Integer.parseInt(definition.group(1)));
        assertFalse(definition.find(), "multiple message_kind definitions found");
    }

    private String readMigration() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(input, "V3_6_0 migration not found on the classpath: " + MIGRATION);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

package org.oagi.score.gateway.http.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3_6_0AiChatSettingsSchemaTest {

    private static final String MIGRATION = "/db/migration/V3_6_0__upgrade_from_3_5_2.sql";

    @Test
    void keepsConversationAsMetadataAndRequiresCompleteSettingsChangeSnapshots() throws IOException {
        String migration;
        try (InputStream input = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(input, "V3_6_0 migration not found on the classpath: " + MIGRATION);
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String conversationTable = migration.substring(
                migration.indexOf("CREATE TABLE `ai_chat_conversation`"),
                migration.indexOf("CREATE TABLE `ai_chat_memory`"));

        assertFalse(conversationTable.contains("`model_name`"));
        assertFalse(conversationTable.contains("`reasoning_effort`"));
        assertFalse(conversationTable.contains("`agent_runtime`"));
        assertFalse(conversationTable.contains("`runtime_options_json`"));
        assertTrue(migration.contains("CONSTRAINT `ai_chat_step_settings_change_ck`"));
        assertTrue(migration.contains("KEY `ai_chat_step_settings_idx`"
                + " (`ai_chat_conversation_id`, `message_kind`, `step_sequence`)"));
        assertTrue(migration.contains("`message_kind` <> 'settings_change'"));
        assertTrue(migration.contains("`model_name` IS NOT NULL AND `reasoning_effort` IS NOT NULL"
                + " AND `agent_runtime` IS NOT NULL"));
        assertTrue(migration.contains("CREATE TABLE `ai_chat_mutation_confirmation`"));
        assertTrue(migration.contains("UNIQUE KEY `ai_chat_mutation_confirmation_grant_uk`"));
        assertTrue(migration.contains("'REQUESTED', 'APPROVED', 'DENIED', 'CONSUMED', 'EXPIRED'"));
        assertTrue(conversationTable.contains("`compacted`"));
    }
}

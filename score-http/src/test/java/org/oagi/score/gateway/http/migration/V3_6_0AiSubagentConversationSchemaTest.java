package org.oagi.score.gateway.http.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3_6_0AiSubagentConversationSchemaTest {

    private static final String MIGRATION = "/db/migration/V3_6_0__upgrade_from_3_5_2.sql";

    @Test
    void addsDurableParentChildConversationIdentity() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("`parent_ai_chat_conversation_id`"));
            assertTrue(sql.contains("`conversation_kind`"));
            assertTrue(sql.contains("`agent_id`"));
            assertTrue(sql.contains("`parent_request_id`"));
            assertTrue(sql.contains("ON DELETE CASCADE"));
            assertTrue(sql.contains("Expected conversation kinds are ROOT, SUBAGENT, and PARALLEL; other values are handled by the application."));
            assertFalse(sql.contains("ai_chat_conversation_kind_ck"));
            assertFalse(sql.contains("ai_chat_conversation_parent_kind_ck"));
            assertFalse(sql.contains("CHECK ("));
            assertFalse(sql.contains("_ck`"));
        }
    }
}

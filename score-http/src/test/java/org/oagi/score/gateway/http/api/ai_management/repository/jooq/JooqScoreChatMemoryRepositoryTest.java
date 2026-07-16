package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Record4;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.jooq.types.ULong;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationSettings;
import org.oagi.score.gateway.http.api.ai_management.repository.ScoreChatMemoryRepository;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_STEP;

class JooqScoreChatMemoryRepositoryTest {

    @Test
    void locksTheConversationThenReadsTheLatestCompleteStepSettingsSnapshot() {
        RecordingProvider provider = new RecordingProvider();
        DSLContext dslContext = DSL.using(new MockConnection(provider), SQLDialect.MYSQL);
        JooqScoreChatMemoryRepository repository =
                new JooqScoreChatMemoryRepository(dslContext, new ObjectMapper());
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());

        AiChatConversationSettings result =
                repository.settingsForUpdate(requester, "conversation-1");

        assertEquals(new AiChatConversationSettings(
                "model", "high", "openai", Map.of("verbosity", "high")), result);
        assertTrue(provider.sql.stream().anyMatch(sql -> sql.contains("for update")));
        assertTrue(provider.sql.stream().anyMatch(sql -> sql.contains("ai_chat_step")
                && sql.contains("order by") && sql.contains("limit")), provider.sql.toString());
    }

    private static final class RecordingProvider implements MockDataProvider {
        private final java.util.ArrayList<String> sql = new java.util.ArrayList<>();

        @Override
        public MockResult[] execute(MockExecuteContext context) {
            String query = context.sql().toLowerCase(Locale.ROOT);
            sql.add(query);
            DSLContext create = DSL.using(SQLDialect.MYSQL);
            if (query.contains("ai_chat_conversation") && query.contains("for update")) {
                Result<Record1<ULong>> result = create.newResult(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
                Record1<ULong> record = create.newRecord(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
                record.value1(ULong.valueOf(42));
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            if (query.contains("ai_chat_step")) {
                Result<Record4<String, String, String, String>> result = create.newResult(
                        AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.REASONING_EFFORT,
                        AI_CHAT_STEP.AGENT_RUNTIME, AI_CHAT_STEP.RUNTIME_OPTIONS_JSON);
                Record4<String, String, String, String> record = create.newRecord(
                        AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.REASONING_EFFORT,
                        AI_CHAT_STEP.AGENT_RUNTIME, AI_CHAT_STEP.RUNTIME_OPTIONS_JSON);
                record.values("model", "high", "openai", "{\"verbosity\":\"high\"}");
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            return new MockResult[]{new MockResult(0, create.newResult())};
        }
    }
}

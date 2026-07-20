package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.Record1;
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
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_CONVERSATION;

class JooqAiChatMemoryStorageRepositoryTest {

    @Test
    void scopesEveryConversationLookupToTheRequester() {
        RecordingProvider provider = new RecordingProvider();
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        JooqAiChatMemoryStorageRepository repository = new JooqAiChatMemoryStorageRepository(
                DSL.using(new MockConnection(provider), SQLDialect.MYSQL), requester, null,
                AiChatJsonSerializer.getInstance());

        repository.findConversationIds();
        repository.findByConversationId("conversation-1");
        repository.saveAll("conversation-1", List.of());
        repository.deleteByConversationId("conversation-1");

        List<String> conversationQueries = provider.sql.stream()
                .filter(sql -> sql.contains("from `oagi`.`ai_chat_conversation`")
                        || sql.contains("join `oagi`.`ai_chat_conversation`"))
                .toList();
        assertThat(conversationQueries).hasSizeGreaterThanOrEqualTo(4)
                .allMatch(sql -> sql.contains("app_user_id"));
    }

    private static final class RecordingProvider implements MockDataProvider {

        private final List<String> sql = new ArrayList<>();

        @Override
        public MockResult[] execute(MockExecuteContext context) {
            String query = context.sql().toLowerCase(Locale.ROOT);
            sql.add(query);
            DSLContext create = DSL.using(SQLDialect.MYSQL);
            if (query.contains("from `oagi`.`ai_chat_conversation`")) {
                Result<Record1<ULong>> result = create.newResult(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
                Record1<ULong> record = create.newRecord(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
                record.value1(ULong.valueOf(42));
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            return new MockResult[]{new MockResult(0, create.newResult())};
        }
    }
}

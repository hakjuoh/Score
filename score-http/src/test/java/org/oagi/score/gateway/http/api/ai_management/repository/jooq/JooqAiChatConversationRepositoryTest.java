package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Record2;
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
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationKind;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_STEP;

class JooqAiChatConversationRepositoryTest {

    @Test
    void generatedStepSchemaContainsNoRuntimeColumns() {
        assertThat(AI_CHAT_STEP.fields())
                .extracting(Field::getName)
                .doesNotContain("agent_runtime", "runtime_options_json");
    }

    @Test
    void createsASubagentConversationLinkedToItsParentRequestAndAgent() {
        RecordingProvider provider = new RecordingProvider();
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        JooqAiChatConversationRepository repository = new JooqAiChatConversationRepository(
                DSL.using(new MockConnection(provider), SQLDialect.MYSQL), requester, null,
                AiChatJsonSerializer.getInstance());

        String child = repository.openChild("conversation-1", "request-7",
                AiChatConversationKind.SUBAGENT, "evidence-researcher",
                "Inspect Sync Purchase Order");

        assertThat(child).isNotBlank();
        assertTrue(provider.sql.stream().anyMatch(sql -> sql.contains("insert into")
                && sql.contains("parent_ai_chat_conversation_id")
                && sql.contains("conversation_kind")
                && sql.contains("agent_id")
                && sql.contains("parent_request_id")), provider.sql.toString());
        assertThat(provider.bindings).anySatisfy(bindings ->
                assertThat(bindings).contains("SUBAGENT"));
    }

    @Test
    void createsAParallelConversationWithoutClassifyingItAsASubagent() {
        RecordingProvider provider = new RecordingProvider();
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        JooqAiChatConversationRepository repository = new JooqAiChatConversationRepository(
                DSL.using(new MockConnection(provider), SQLDialect.MYSQL), requester, null,
                AiChatJsonSerializer.getInstance());

        repository.openChild("conversation-1", "request-8",
                AiChatConversationKind.PARALLEL, "evidence-researcher",
                "Inspect Get Purchase Order");

        assertThat(provider.bindings).anySatisfy(bindings -> {
            assertThat(bindings).contains("PARALLEL");
            assertThat(bindings).doesNotContain("SUBAGENT");
        });
    }

    @Test
    void locksTheConversationThenReadsTheLatestCompleteStepSettingsSnapshot() {
        RecordingProvider provider = new RecordingProvider();
        DSLContext dslContext = DSL.using(new MockConnection(provider), SQLDialect.MYSQL);
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        JooqAiChatConversationRepository repository =
                new JooqAiChatConversationRepository(
                        dslContext, requester, null, AiChatJsonSerializer.getInstance());

        AiChatConversationSettings result =
                repository.settingsForUpdate("conversation-1");

        assertEquals(new AiChatConversationSettings("model", "high"), result);
        assertTrue(provider.sql.stream().anyMatch(sql -> sql.contains("for update")));
        assertTrue(provider.sql.stream().anyMatch(sql -> sql.contains("ai_chat_step")
                && sql.contains("order by") && sql.contains("limit")), provider.sql.toString());
    }

    @Test
    void readsTheLatestPersistedActiveWorkflowIndependentlyFromModelSettings() {
        RecordingProvider provider = new RecordingProvider();
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        JooqAiChatConversationRepository repository = new JooqAiChatConversationRepository(
                DSL.using(new MockConnection(provider), SQLDialect.MYSQL), requester, null,
                AiChatJsonSerializer.getInstance());

        assertThat(repository.activeWorkflow("conversation-1"))
                .contains("orchestrator_workers");
        assertTrue(provider.sql.stream().anyMatch(sql -> sql.contains("extra_json")
                && sql.contains("order by") && sql.contains("limit")), provider.sql.toString());
    }

    @Test
    void rejectsAnIncompleteStoredSettingsChangeWithAClearError() {
        assertThatThrownBy(() -> JooqAiChatConversationRepository.validateStoredSettingsChange(
                "settings_change", "model", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "settings_change", "reasoning_effort", "no safe defaults");
    }

    @Test
    void loadsTheCompleteRootAndChildTrajectoryWithoutARepositoryLimit() {
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        TrajectoryProvider provider = new TrajectoryProvider();
        JooqAiChatConversationRepository repository = new JooqAiChatConversationRepository(
                DSL.using(new MockConnection(provider), SQLDialect.MYSQL),
                requester, null, AiChatJsonSerializer.getInstance());

        AiChatTrajectoryData data = repository.getTrajectoryData("conversation-1");

        assertThat(data.conversationId()).isEqualTo("conversation-1");
        assertThat(data.totalStoredSteps()).isEqualTo(6);
        assertThat(data.truncated()).isFalse();
        assertThat(data.steps()).extracting(AiChatTrajectoryData.Step::messageKind)
                .containsExactly("model_call", "assistant");
        assertThat(data.childTrajectories()).singleElement().satisfies(child -> {
            assertThat(child.trajectoryId()).isEqualTo("child-1");
            assertThat(child.conversationKind()).isEqualTo("SUBAGENT");
            assertThat(child.agentId()).isEqualTo("researcher");
            assertThat(child.parentRequestId()).isEqualTo("request-1");
            assertThat(child.steps()).hasSize(4);
        });
        assertThat(provider.sql.stream()
                .filter(sql -> sql.contains("from `oagi`.`ai_chat_step`")))
                .noneMatch(sql -> sql.contains(" limit "));
    }

    private static final class RecordingProvider implements MockDataProvider {
        private final java.util.ArrayList<String> sql = new java.util.ArrayList<>();
        private final java.util.ArrayList<List<Object>> bindings = new java.util.ArrayList<>();

        @Override
        public MockResult[] execute(MockExecuteContext context) {
            String query = context.sql().toLowerCase(Locale.ROOT);
            sql.add(query);
            bindings.add(List.of(context.bindings()));
            DSLContext create = DSL.using(SQLDialect.MYSQL);
            if (query.contains("from `oagi`.`ai_chat_conversation`")
                    && query.contains("for update")) {
                Result<Record1<ULong>> result = create.newResult(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
                Record1<ULong> record = create.newRecord(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
                record.value1(ULong.valueOf(42));
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            if (query.contains("from `oagi`.`ai_chat_conversation`")
                    && query.contains("select")) {
                Result<Record1<ULong>> result = create.newResult(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
                Record1<ULong> record = create.newRecord(
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
                record.value1(ULong.valueOf(42));
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            if (query.contains("ai_chat_step") && query.contains("extra_json")) {
                Result<Record1<String>> result = create.newResult(AI_CHAT_STEP.EXTRA_JSON);
                Record1<String> record = create.newRecord(AI_CHAT_STEP.EXTRA_JSON);
                record.value1("{\"activeWorkflow\":\"orchestrator_workers\"}");
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            if (query.contains("ai_chat_step")) {
                Result<Record2<String, String>> result = create.newResult(
                        AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.REASONING_EFFORT);
                Record2<String, String> record = create.newRecord(
                        AI_CHAT_STEP.MODEL_NAME, AI_CHAT_STEP.REASONING_EFFORT);
                record.values("model", "high");
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            return new MockResult[]{new MockResult(0, create.newResult())};
        }
    }

    private static final class TrajectoryProvider implements MockDataProvider {

        private final java.util.ArrayList<String> sql = new java.util.ArrayList<>();

        @Override
        public MockResult[] execute(MockExecuteContext context) {
            String query = context.sql().toLowerCase(Locale.ROOT);
            sql.add(query);
            DSLContext create = DSL.using(SQLDialect.MYSQL);
            if (query.contains("from `oagi`.`ai_chat_step`")) {
                return new MockResult[]{new MockResult(6, steps(create))};
            }
            if (query.contains("from `oagi`.`ai_chat_conversation`")
                    && query.contains("parent_ai_chat_conversation_id")) {
                Field<?>[] fields = {
                        AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID,
                        AI_CHAT_CONVERSATION.GUID,
                        AI_CHAT_CONVERSATION.CONVERSATION_KIND,
                        AI_CHAT_CONVERSATION.AGENT_ID,
                        AI_CHAT_CONVERSATION.PARENT_REQUEST_ID
                };
                Result<Record> result = create.newResult(fields);
                Record record = create.newRecord(fields);
                record.set(AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID, ULong.valueOf(43));
                record.set(AI_CHAT_CONVERSATION.GUID, "child-1");
                record.set(AI_CHAT_CONVERSATION.CONVERSATION_KIND, "SUBAGENT");
                record.set(AI_CHAT_CONVERSATION.AGENT_ID, "researcher");
                record.set(AI_CHAT_CONVERSATION.PARENT_REQUEST_ID, "request-1");
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
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

        private Result<Record> steps(DSLContext create) {
            Field<?>[] fields = {
                    AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID, AI_CHAT_STEP.STEP_SEQUENCE,
                    AI_CHAT_STEP.REQUEST_ID, AI_CHAT_STEP.SOURCE, AI_CHAT_STEP.MESSAGE_KIND,
                    AI_CHAT_STEP.VISIBILITY, AI_CHAT_STEP.MESSAGE,
                    AI_CHAT_STEP.REASONING_CONTENT, AI_CHAT_STEP.MODEL_NAME,
                    AI_CHAT_STEP.REASONING_EFFORT, AI_CHAT_STEP.TOOL_CALLS_JSON,
                    AI_CHAT_STEP.OBSERVATION_JSON, AI_CHAT_STEP.METRICS_JSON,
                    AI_CHAT_STEP.EXTRA_JSON, AI_CHAT_STEP.LLM_CALL_COUNT,
                    AI_CHAT_STEP.IS_COPIED_CONTEXT, AI_CHAT_STEP.CREATED_AT
            };
            Result<Record> result = create.newResult(fields);
            addStep(create, result, ULong.valueOf(42), 1L, "agent", "model_call",
                    "debug", "Hello", "{\"prompt_tokens\":10,\"completion_tokens\":2,"
                            + "\"context_input_tokens\":100,\"context_estimated\":true}",
                    "{\"phase\":\"assistant\"}", 1, null,
                    LocalDateTime.parse("2026-07-20T17:43:30"));
            addStep(create, result, ULong.valueOf(42), 2L, "agent", "assistant",
                    "visible", "Hello", null, "{\"ui_projection\":true}", 0, null,
                    LocalDateTime.parse("2026-07-20T17:43:31"));
            addStep(create, result, ULong.valueOf(43), 1L, "system", "settings_change",
                    "debug", "Child execution settings initialized.", null,
                    "{\"agent_id\":\"researcher\"}", 0, null,
                    LocalDateTime.parse("2026-07-20T17:43:20"));
            addStep(create, result, ULong.valueOf(43), 2L, "user", "assignment",
                    "visible", "Research the record", null,
                    "{\"copied_from_parent\":true}", 0, (byte) 1,
                    LocalDateTime.parse("2026-07-20T17:43:21"));
            addStep(create, result, ULong.valueOf(43), 3L, "agent", "model_call",
                    "debug", "Research complete", "{\"prompt_tokens\":5,\"completion_tokens\":3,"
                            + "\"context_scope\":\"subagent\"}",
                    "{\"phase\":\"assistant\"}", 1, null,
                    LocalDateTime.parse("2026-07-20T17:43:22"));
            addStep(create, result, ULong.valueOf(43), 4L, "agent", "assistant",
                    "visible", "Research complete", null, "{\"ui_projection\":true}", 0, null,
                    LocalDateTime.parse("2026-07-20T17:43:23"));
            result.sort((left, right) -> right.get(AI_CHAT_STEP.CREATED_AT)
                    .compareTo(left.get(AI_CHAT_STEP.CREATED_AT)));
            return result;
        }

        private void addStep(DSLContext create, Result<Record> result,
                             ULong conversationId, long sequence,
                             String source, String kind, String visibility, String message,
                             String metrics, String extra, Integer llmCalls, Byte copied,
                             LocalDateTime createdAt) {
            Record record = create.newRecord(result.fields());
            record.set(AI_CHAT_STEP.AI_CHAT_CONVERSATION_ID, conversationId);
            record.set(AI_CHAT_STEP.STEP_SEQUENCE, sequence);
            record.set(AI_CHAT_STEP.REQUEST_ID, "request-1");
            record.set(AI_CHAT_STEP.SOURCE, source);
            record.set(AI_CHAT_STEP.MESSAGE_KIND, kind);
            record.set(AI_CHAT_STEP.VISIBILITY, visibility);
            record.set(AI_CHAT_STEP.MESSAGE, message);
            record.set(AI_CHAT_STEP.MODEL_NAME, "model");
            record.set(AI_CHAT_STEP.REASONING_EFFORT, "medium");
            record.set(AI_CHAT_STEP.METRICS_JSON, metrics);
            record.set(AI_CHAT_STEP.EXTRA_JSON, extra);
            record.set(AI_CHAT_STEP.LLM_CALL_COUNT, llmCalls);
            record.set(AI_CHAT_STEP.IS_COPIED_CONTEXT, copied);
            record.set(AI_CHAT_STEP.CREATED_AT, createdAt);
            result.add(record);
        }
    }

}

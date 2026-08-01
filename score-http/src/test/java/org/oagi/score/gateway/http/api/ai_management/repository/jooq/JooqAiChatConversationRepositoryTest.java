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
import org.oagi.score.gateway.http.api.ai_management.model.AiChatConversationId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStoredStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatStepId;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryData;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatHistoryMessage;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_CONVERSATION;
import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.AI_CHAT_STEP;

class JooqAiChatConversationRepositoryTest {

    @Test
    void keepsEveryTransactionAtThePublicFacadeAfterRepositorySeparation() throws Exception {
        Set<String> readOnly = Set.of("modelName", "settings", "activeWorkflow",
                "latestUsage", "list", "get", "getTrajectoryData");
        for (Method contract : AiChatConversationRepository.class.getDeclaredMethods()) {
            Method facade = JooqAiChatConversationRepository.class.getMethod(
                    contract.getName(), contract.getParameterTypes());
            Transactional transaction = facade.getAnnotation(Transactional.class);
            assertThat(transaction).as(contract.getName()).isNotNull();
            assertThat(transaction.readOnly()).as(contract.getName())
                    .isEqualTo(readOnly.contains(contract.getName()));
        }
        assertThat(Modifier.isPublic(JooqAiChatConversationCommands.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(JooqAiChatConversationQueries.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(JooqAiChatConversationAccess.class.getModifiers())).isFalse();
    }

    @Test
    void ownershipAccessAlwaysScopesGuidByRequesterAndFailsClosed() {
        OwnershipProvider owned = new OwnershipProvider(true);
        JooqAiChatConversationAccess access = new JooqAiChatConversationAccess(
                DSL.using(new MockConnection(owned), SQLDialect.MYSQL),
                new UserId(BigInteger.valueOf(9)));

        assertThat(access.ownedId("conversation-1"))
                .isEqualTo(AiChatConversationId.from(42L));
        assertThat(access.lockOwned("conversation-1"))
                .isEqualTo(AiChatConversationId.from(42L));
        assertThat(owned.sql).hasSize(2).allSatisfy(sql ->
                assertThat(sql).contains("where (`oagi`.`ai_chat_conversation`.`guid` = ?"
                        + " and `oagi`.`ai_chat_conversation`.`app_user_id` = ?)"));
        assertThat(owned.bindings).allSatisfy(bindings ->
                assertThat(bindings).extracting(Object::toString)
                        .contains("conversation-1", "9"));
        assertThat(owned.sql.get(0)).doesNotContain("for update");
        assertThat(owned.sql.get(1)).contains("for update");

        OwnershipProvider missing = new OwnershipProvider(false);
        JooqAiChatConversationAccess denied = new JooqAiChatConversationAccess(
                DSL.using(new MockConnection(missing), SQLDialect.MYSQL),
                new UserId(BigInteger.valueOf(9)));
        assertThatThrownBy(() -> denied.ownedId("missing"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> denied.lockOwned("missing"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> denied.requireOwned("missing"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void restoresOnlyTheCanonicalFinalAnswerAcrossWorkflowIterations() {
        ChatHistoryMessage firstIteration = history(0, "workflow_result", "First answer.");
        ChatHistoryMessage finalPreview = history(1, "workflow_result", "Final answer.");
        ChatHistoryMessage finalAnswer = history(2, null, "Final answer.");

        assertThat(JooqAiChatConversationRepository.coalesceFinalWorkflowResult(List.of(
                firstIteration, finalPreview, finalAnswer)))
                .containsExactly(finalAnswer);
    }

    @Test
    void retainsOnlyTheLatestWorkflowResultWhenNoCanonicalAnswerWasPersisted() {
        ChatHistoryMessage firstIteration = history(0, "workflow_result", "First answer.");
        ChatHistoryMessage lastSafePreview = history(1, "workflow_result", "Last safe answer.");

        assertThat(JooqAiChatConversationRepository.coalesceFinalWorkflowResult(List.of(
                firstIteration, lastSafePreview)))
                .containsExactly(lastSafePreview);
    }

    private static ChatHistoryMessage history(int index, String subtype, String content) {
        return new ChatHistoryMessage(index, "assistant", content,
                "request-1", "request-1", null, null, null,
                subtype, "visible", Map.of());
    }

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
    void appendsUnderTheOwnershipLockAndAdvancesConversationTimestamp() {
        MutationProvider provider = new MutationProvider();
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        JooqAiChatConversationRepository repository = new JooqAiChatConversationRepository(
                DSL.using(new MockConnection(provider), SQLDialect.MYSQL), requester, null,
                AiChatJsonSerializer.getInstance());
        Instant createdAt = Instant.parse("2026-07-20T17:43:30Z");
        AiChatTrajectoryStep step = new AiChatTrajectoryStep(
                "request-1", "agent", "assistant", "visible", "done", null,
                "model", "high", null, null, null, Map.of("safe", true),
                1, false, createdAt);

        AiChatStoredStep stored = repository.append("conversation-1", step);

        assertThat(stored).isEqualTo(
                new AiChatStoredStep(AiChatStepId.from(77L), 5L, createdAt));
        assertThat(provider.sql).anyMatch(sql -> sql.contains("for update"));
        assertThat(provider.sql).anyMatch(sql -> sql.contains("max(")
                && sql.contains("step_sequence"));
        assertThat(provider.sql).anyMatch(sql -> sql.contains("insert into")
                && sql.contains("ai_chat_step"));
        assertThat(provider.sql).anyMatch(sql -> sql.contains("update")
                && sql.contains("ai_chat_conversation")
                && sql.contains("last_update_timestamp"));
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
    void rejectsIncompleteChildIdentityBeforeIssuingSql() {
        RecordingProvider provider = new RecordingProvider();
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        JooqAiChatConversationRepository repository = new JooqAiChatConversationRepository(
                DSL.using(new MockConnection(provider), SQLDialect.MYSQL), requester, null,
                AiChatJsonSerializer.getInstance());

        assertThatThrownBy(() -> repository.openChild("conversation-1", "request-1",
                AiChatConversationKind.ROOT, "worker", "title"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.openChild("conversation-1", "request-1",
                AiChatConversationKind.SUBAGENT, "  ", "title"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.sql).isEmpty();
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
        int lockIndex = indexOf(provider.sql, sql -> sql.contains("ai_chat_conversation")
                && sql.contains("for update"));
        int settingsIndex = indexOf(provider.sql, sql -> sql.contains("ai_chat_step"));
        assertThat(lockIndex).isGreaterThanOrEqualTo(0).isLessThan(settingsIndex);
        assertThat(provider.sql.get(settingsIndex))
                .contains("message_kind` = ?",
                        "order by `oagi`.`ai_chat_step`.`step_sequence` desc", "limit ?");
        assertThat(provider.bindings.get(settingsIndex)).contains("settings_change", 1L);
    }

    @Test
    void zeroRowTrajectoryUpdatesFailAndModelCompletionKeepsItsKindPredicate() {
        ZeroUpdateProvider provider = new ZeroUpdateProvider();
        ScoreUser requester = new ScoreUser(new UserId(BigInteger.ONE), "tester", "Test User",
                null, false, List.of());
        JooqAiChatConversationRepository repository = new JooqAiChatConversationRepository(
                DSL.using(new MockConnection(provider), SQLDialect.MYSQL), requester, null,
                AiChatJsonSerializer.getInstance());
        AiChatTrajectoryStep modelCall = new AiChatTrajectoryStep(
                "request-1", "agent", "model_call", "debug", "", null,
                "model", "high", List.of(), Map.of(), Map.of(), Map.of(), 1, null,
                Instant.parse("2026-07-20T17:43:30Z"));

        assertThatThrownBy(() -> repository.updateObservation(
                "conversation-1", AiChatStepId.from(77L), Map.of("status", "done")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trajectory step");
        assertThatThrownBy(() -> repository.updateModelCall(
                "conversation-1", AiChatStepId.from(77L), modelCall))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model-call trajectory step");
        assertThat(provider.sql.stream()
                .filter(sql -> sql.startsWith("update") && sql.contains("model_name"))
                .toList()).singleElement().satisfies(sql ->
                assertThat(sql).contains("message_kind` = ?", "ai_chat_conversation_id` = ?"));
        assertThat(provider.bindings).anySatisfy(bindings ->
                assertThat(bindings).extracting(value -> Objects.toString(value, "null"))
                        .contains("model_call", "42", "77"));
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
        assertThat(data.steps()).extracting(AiChatTrajectoryData.Step::sequence)
                .containsExactly(1L, 2L);
        assertThat(data.childTrajectories()).singleElement().satisfies(child -> {
            assertThat(child.trajectoryId()).isEqualTo("child-1");
            assertThat(child.conversationKind()).isEqualTo("SUBAGENT");
            assertThat(child.agentId()).isEqualTo("researcher");
            assertThat(child.parentRequestId()).isEqualTo("request-1");
            assertThat(child.steps()).extracting(AiChatTrajectoryData.Step::sequence)
                    .containsExactly(1L, 2L, 3L, 4L);
        });
        assertThat(provider.sql.stream()
                .filter(sql -> sql.contains("from `oagi`.`ai_chat_step`")))
                .noneMatch(sql -> sql.contains(" limit "));
        assertThat(provider.sql.stream()
                .filter(sql -> sql.contains("from `oagi`.`ai_chat_step`"))
                .toList()).singleElement().satisfies(sql ->
                assertThat(sql).contains("order by `oagi`.`ai_chat_step`.`creation_timestamp` desc",
                        "`oagi`.`ai_chat_step`.`ai_chat_step_id` desc"));
    }

    private static int indexOf(List<String> values,
                               java.util.function.Predicate<String> predicate) {
        for (int index = 0; index < values.size(); index++) {
            if (predicate.test(values.get(index))) return index;
        }
        return -1;
    }

    private static final class RecordingProvider implements MockDataProvider {
        private final java.util.ArrayList<String> sql = new java.util.ArrayList<>();
        private final java.util.ArrayList<List<Object>> bindings = new java.util.ArrayList<>();

        @Override
        public MockResult[] execute(MockExecuteContext context) {
            String query = context.sql().toLowerCase(Locale.ROOT);
            sql.add(query);
            bindings.add(java.util.Arrays.asList(context.bindings()));
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

    private static final class OwnershipProvider implements MockDataProvider {

        private final boolean found;
        private final java.util.ArrayList<String> sql = new java.util.ArrayList<>();
        private final java.util.ArrayList<List<Object>> bindings = new java.util.ArrayList<>();

        private OwnershipProvider(boolean found) {
            this.found = found;
        }

        @Override
        public MockResult[] execute(MockExecuteContext context) {
            String query = context.sql().toLowerCase(Locale.ROOT);
            sql.add(query);
            bindings.add(List.of(context.bindings()));
            DSLContext create = DSL.using(SQLDialect.MYSQL);
            if (query.contains("select exists")) {
                Field<Boolean> exists = DSL.field("exists", Boolean.class);
                Result<Record1<Boolean>> result = create.newResult(exists);
                Record1<Boolean> record = create.newRecord(exists);
                record.value1(found);
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            if (!found) return new MockResult[]{new MockResult(0, create.newResult())};
            Result<Record1<ULong>> result = create.newResult(
                    AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
            Record1<ULong> record = create.newRecord(
                    AI_CHAT_CONVERSATION.AI_CHAT_CONVERSATION_ID);
            record.value1(ULong.valueOf(42));
            result.add(record);
            return new MockResult[]{new MockResult(1, result)};
        }
    }

    private static final class ZeroUpdateProvider implements MockDataProvider {

        private final java.util.ArrayList<String> sql = new java.util.ArrayList<>();
        private final java.util.ArrayList<List<Object>> bindings = new java.util.ArrayList<>();

        @Override
        public MockResult[] execute(MockExecuteContext context) {
            String query = context.sql().toLowerCase(Locale.ROOT);
            sql.add(query);
            bindings.add(java.util.Arrays.asList(context.bindings()));
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
                    AI_CHAT_STEP.IS_COPIED_CONTEXT, AI_CHAT_STEP.CREATION_TIMESTAMP
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
            result.sort((left, right) -> right.get(AI_CHAT_STEP.CREATION_TIMESTAMP)
                    .compareTo(left.get(AI_CHAT_STEP.CREATION_TIMESTAMP)));
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
            record.set(AI_CHAT_STEP.CREATION_TIMESTAMP, createdAt);
            result.add(record);
        }
    }

    private static final class MutationProvider implements MockDataProvider {

        private final java.util.ArrayList<String> sql = new java.util.ArrayList<>();

        @Override
        public MockResult[] execute(MockExecuteContext context) {
            String query = context.sql().toLowerCase(Locale.ROOT);
            sql.add(query);
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
            if (query.contains("max(") && query.contains("step_sequence")) {
                Field<Long> nextSequence = DSL.field("next_sequence", Long.class);
                Result<Record1<Long>> result = create.newResult(nextSequence);
                Record1<Long> record = create.newRecord(nextSequence);
                record.value1(5L);
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            if (query.contains("insert into") && query.contains("ai_chat_step")) {
                Result<Record1<ULong>> result = create.newResult(AI_CHAT_STEP.AI_CHAT_STEP_ID);
                Record1<ULong> record = create.newRecord(AI_CHAT_STEP.AI_CHAT_STEP_ID);
                record.value1(ULong.valueOf(77));
                result.add(record);
                return new MockResult[]{new MockResult(1, result)};
            }
            return new MockResult[]{new MockResult(1, create.newResult())};
        }
    }

}

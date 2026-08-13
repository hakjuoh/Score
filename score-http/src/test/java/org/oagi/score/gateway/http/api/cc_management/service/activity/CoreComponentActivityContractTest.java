package org.oagi.score.gateway.http.api.cc_management.service.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListManifestId;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreHttpRequest;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.ManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bccp.BccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt_sc.DtScManifestId;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListManifestId;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CoreComponentActivityContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ScoreUser requester = new ScoreUser(
            new UserId(BigInteger.valueOf(7)), "developer", "Developer", null, false,
            List.of(ScoreRole.DEVELOPER));
    private final CoreComponentActivityEventFactory factory =
            CoreComponentActivityTestSupport.eventFactory();

    @Test
    void everyConfiguredComponentAndActionMatchesTheSharedSchemaAndCatalog() throws Exception {
        Set<String> expectedNames = new HashSet<>();
        Schema schema = activitySchema();
        for (ComponentCase component : components()) {
            List<ScoreActivityEvent> events = events(component);
            assertThat(events).hasSize(8).allSatisfy(event -> {
                assertThat(schema.validate(objectMapper.valueToTree(event))).isEmpty();
                assertThat(event.targets()).allSatisfy(target ->
                        assertThat(target.type()).isEqualTo(component.targetType()));
            });
            events.forEach(event -> expectedNames.add(event.name()));
        }

        JsonNode catalog = objectMapper.readTree(
                Files.readString(repositoryContract("score-activity-event-catalog.json")));
        Set<String> catalogNames = new HashSet<>();
        catalog.path("events").forEach(event -> catalogNames.add(event.path("name").asText()));
        assertThat(catalogNames).containsAll(expectedNames);
        assertThat(expectedNames).hasSize(12);
    }

    @Test
    void schemaRejectsCrossCategoryTargetsFieldsAndOutcomeShapes() throws Exception {
        ObjectNode wrongTarget = objectMapper.valueToTree(factory.updated(
                "asccp", requester, new AsccpManifestId(BigInteger.ONE),
                List.of("propertyTerm")));
        ((ObjectNode) wrongTarget.at("/targets/0")).put("type", "BCCP");
        assertThat(activitySchema().validate(wrongTarget)).isNotEmpty();

        ObjectNode wrongField = objectMapper.valueToTree(factory.updated(
                "bccp", requester, new BccpManifestId(BigInteger.ONE),
                List.of("roleOfAccManifestId")));
        assertThat(activitySchema().validate(wrongField)).isNotEmpty();

        ObjectNode successWithError = objectMapper.valueToTree(factory.stateChanged(
                "acc", requester, new AccManifestId(BigInteger.ONE), CcState.Deleted));
        ((ObjectNode) successWithError.path("properties")).put("errorCode", "INVALID_STATE");
        assertThat(activitySchema().validate(successWithError)).isNotEmpty();

        ObjectNode malformedTrace = objectMapper.valueToTree(factory.stateChanged(
                "acc", requester, new AccManifestId(BigInteger.ONE), CcState.Deleted));
        ((ObjectNode) malformedTrace.path("context")).put("traceId", "trace-1");
        ((ObjectNode) malformedTrace.path("context")).put("spanId", "0000000000000000");
        assertThat(activitySchema().validate(malformedTrace)).isNotEmpty();

        ObjectNode wrongCompositeTarget = objectMapper.valueToTree(operation(
                "oagis-bod", "create", "generate-bod", "ACC",
                new AccManifestId(BigInteger.valueOf(99))));
        assertThat(activitySchema().validate(wrongCompositeTarget)).isNotEmpty();
    }

    @Test
    void catalogExactlyDescribesSupportedNamesSourcesPropertiesAndFailureCodes() throws Exception {
        JsonNode catalog = objectMapper.readTree(
                Files.readString(repositoryContract("score-activity-event-catalog.json")));
        Set<String> lifecycleCategories = Set.of("acc", "asccp", "bccp");
        Set<String> expectedNames = new HashSet<>();
        lifecycleCategories.forEach(category -> List.of("create", "update", "state-change", "delete")
                .forEach(action -> expectedNames.add(category + "." + action)));
        expectedNames.addAll(Set.of(
                "dt.state-change", "code-list.state-change",
                "agency-id-list.state-change", "release.state-change",
                "dt.create", "dt.update", "dt.delete",
                "ascc.create", "ascc.update", "ascc.delete",
                "bcc.create", "bcc.update", "bcc.delete",
                "dt-sc.create", "dt-sc.update", "dt-sc.delete",
                "oagis-bod.create", "oagis-verb.create"));
        Set<String> sources = Set.of(
                "SCORE_HTTP_API", "CONNECT_CENTER_REST", "CONNECT_CENTER_MCP", "AI_ASSISTANT");
        Set<String> commonFailureCodes = Set.of(
                "ACCESS_DENIED", "VALIDATION_ERROR", "INVALID_STATE", "TARGET_NOT_FOUND",
                "NOT_APPLIED", "INTERNAL_ERROR");
        Set<String> allFailureCodes = new HashSet<>(commonFailureCodes);
        allFailureCodes.add("BATCH_ROLLED_BACK");

        Map<String, JsonNode> eventsByName = new java.util.HashMap<>();
        catalog.path("events").forEach(event -> eventsByName.put(event.path("name").asText(), event));
        assertThat(eventsByName.keySet()).isEqualTo(expectedNames);
        assertThat(fieldNames(catalog.at("/propertyTypes/scoreActivityFailureCode/values")))
                .isEqualTo(allFailureCodes);
        eventsByName.forEach((name, event) -> {
            Set<String> expectedKeys = expectedPropertyKeys(name);
            assertThat(toTextSet(event.path("allowedSources")))
                    .as(name + " sources").isEqualTo(sources);
            assertThat(toTextSet(event.path("allowedPropertyKeys")))
                    .as(name + " property keys").isEqualTo(expectedKeys);
            assertThat(toTextSet(event.path("allowedErrorCodes")))
                    .as(name + " failure codes")
                    .isEqualTo(Set.of("acc.update", "asccp.update", "bccp.update", "dt.update").contains(name)
                            ? allFailureCodes : commonFailureCodes);
        });
    }

    @Test
    void compositeAndNestedOperationEventsMatchTheSharedSchema() throws Exception {
        Schema schema = activitySchema();
        List<ScoreActivityEvent> events = List.of(
                operation("dt", "create", "create-dt", "DT", new DtManifestId(BigInteger.valueOf(50))),
                operation("ascc", "create", "append", "ASCC", new AsccManifestId(BigInteger.valueOf(51))),
                operation("ascc", "update", "modify", "ASCC", new AsccManifestId(BigInteger.valueOf(51))),
                operation("ascc", "delete", "discard", "ASCC", new AsccManifestId(BigInteger.valueOf(51))),
                operation("bcc", "create", "append", "BCC", new BccManifestId(BigInteger.valueOf(52))),
                operation("dt-sc", "create", "append", "DT_SC", new DtScManifestId(BigInteger.valueOf(53))),
                operation("oagis-bod", "create", "generate-bod", "ASCCP", new AsccpManifestId(BigInteger.valueOf(54))),
                operation("oagis-verb", "create", "generate-verb", "ASCCP", new AsccpManifestId(BigInteger.valueOf(55))));

        assertThat(events).allSatisfy(event ->
                assertThat(schema.validate(objectMapper.valueToTree(event))).as(event.name()).isEmpty());
    }

    @Test
    void releaseManagedComponentStateChangesMatchTheSharedSchemaAndCatalog() throws Exception {
        List<ComponentCase> components = List.of(
                new ComponentCase("dt", "DT", new DtManifestId(BigInteger.valueOf(44)), List.of()),
                new ComponentCase("code-list", "CODE_LIST",
                        new CodeListManifestId(BigInteger.valueOf(45)), List.of()),
                new ComponentCase("agency-id-list", "AGENCY_ID_LIST",
                        new AgencyIdListManifestId(BigInteger.valueOf(46)), List.of()));
        Schema schema = activitySchema();
        JsonNode catalog = objectMapper.readTree(
                Files.readString(repositoryContract("score-activity-event-catalog.json")));
        Set<String> catalogNames = new HashSet<>();
        catalog.path("events").forEach(event -> catalogNames.add(event.path("name").asText()));

        for (ComponentCase component : components) {
            ScoreActivityEvent event = factory.stateChanged(
                    component.category(), requester, component.id(), CcState.ReleaseDraft);
            assertThat(schema.validate(objectMapper.valueToTree(event))).isEmpty();
            assertThat(event.targets().getFirst().type()).isEqualTo(component.targetType());
            assertThat(catalogNames).contains(event.name());
        }
    }

    @Test
    void httpProxyMetadataMatchesTheLanguageNeutralContract() throws Exception {
        ScoreActivityEvent base = factory.created(
                "acc", requester, new AccManifestId(BigInteger.ONE));
        ScoreHttpRequest request = new ScoreHttpRequest(
                "POST", null, "https", "score.example.org", 443,
                "/api/core-components/acc", null, "2", "203.0.113.10",
                "10.0.0.5", 51234, "Mozilla/5.0", "https://score.example.org",
                "https://score.example.org/core_component/acc", "3.6.0-dev",
                "Apache Tomcat/11.0", "x-forwarded-for",
                Map.of(
                        "x-forwarded-for", List.of("203.0.113.10, 10.0.0.5"),
                        "cf-ray", List.of("abc123-EWR")));
        ScoreActivityEvent event = base.withContext(new ScoreActivityContext(
                "0123456789abcdef0123456789abcdef", "0123456789abcdef",
                null, "00", null, "2026-08-07T00:00:00Z",
                "ACC_CREATE", "request-1", "2026-08-07T00:00:00Z",
                request));

        ObjectNode valid = objectMapper.valueToTree(event);
        assertThat(activitySchema().validate(valid)).isEmpty();

        ObjectNode invalid = valid.deepCopy();
        ((ObjectNode) invalid.at("/context/httpRequest/proxyHeaders"))
                .putArray("authorization").add("Bearer secret");
        assertThat(activitySchema().validate(invalid)).isNotEmpty();
    }

    private List<ScoreActivityEvent> events(ComponentCase component) {
        List<ScoreActivityEvent> events = new ArrayList<>();
        events.add(factory.created(component.category(), requester, component.id()));
        events.add(factory.createFailed(
                component.category(), requester, ScoreActivityFailureCode.VALIDATION_ERROR));
        events.add(factory.updated(
                component.category(), requester, component.id(), component.updateFields()));
        events.add(factory.updateFailed(
                component.category(), requester, component.id(), component.updateFields(),
                ScoreActivityFailureCode.NOT_APPLIED));
        events.add(factory.stateChanged(
                component.category(), requester, component.id(), CcState.Deleted));
        events.add(factory.stateChangeFailed(
                component.category(), requester, component.id(), CcState.Deleted,
                ScoreActivityFailureCode.INVALID_STATE));
        events.add(factory.deleted(component.category(), requester, component.id()));
        events.add(factory.deleteFailed(
                component.category(), requester, component.id(),
                ScoreActivityFailureCode.TARGET_NOT_FOUND));
        return events;
    }

    private Schema activitySchema() throws Exception {
        String schema = Files.readString(repositoryContract("score-activity-event.schema.json"));
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schema);
    }

    private static Path repositoryContract(String fileName) {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path repositoryRoot = "score-http".equals(workingDirectory.getFileName().toString())
                ? workingDirectory.getParent()
                : workingDirectory;
        return repositoryRoot.resolve("contracts").resolve(fileName);
    }

    private static Set<String> toTextSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private ScoreActivityEvent operation(
            String category, String action, String operation, String targetType, ManifestId id) {
        return factory.operationSucceeded(category, action, requester,
                List.of(org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget.primary(
                        targetType, id, new org.oagi.score.gateway.http.common.model.Guid(
                                "0123456789abcdef0123456789abcdef"), category + " name")),
                Map.of("operation", operation));
    }

    private static Set<String> expectedPropertyKeys(String name) {
        if ("release.state-change".equals(name)) {
            return Set.of("toState", "completionStage", "errorCode");
        }
        if (Set.of("code-list.state-change", "agency-id-list.state-change").contains(name)) {
            return Set.of("fromState", "toState", "errorCode");
        }
        if (name.endsWith(".state-change")) {
            return Set.of("fromState", "toState", "operation", "errorCode");
        }
        if (Set.of("acc.update", "asccp.update", "bccp.update").contains(name)) {
            return Set.of("requestedFields", "operation", "errorCode");
        }
        if (Set.of(
                "acc.create", "asccp.create", "bccp.create",
                "acc.delete", "asccp.delete", "bccp.delete").contains(name)) {
            return Set.of("errorCode");
        }
        return Set.of("operation", "errorCode");
    }

    private static List<ComponentCase> components() {
        return List.of(
                new ComponentCase(
                        "acc", "ACC", new AccManifestId(BigInteger.valueOf(41)),
                        List.of("objectClassTerm", "namespaceId")),
                new ComponentCase(
                        "asccp", "ASCCP", new AsccpManifestId(BigInteger.valueOf(42)),
                        List.of("propertyTerm")),
                new ComponentCase(
                        "bccp", "BCCP", new BccpManifestId(BigInteger.valueOf(43)),
                        List.of("propertyTerm")));
    }

    private record ComponentCase(
            String category,
            String targetType,
            ManifestId id,
            List<String> updateFields) {
    }
}

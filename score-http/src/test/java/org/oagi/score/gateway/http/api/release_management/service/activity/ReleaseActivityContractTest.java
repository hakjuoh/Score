package org.oagi.score.gateway.http.api.release_management.service.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.release_management.controller.payload.TransitStateRequest;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseActivityContractTest {

    @Test
    void releaseStateChangeMatchesTheSharedWireSchemaAndCatalog() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ReleaseActivityHandler handler = new ReleaseActivityHandler(
                new ScoreActivityEventFactory(Clock.systemUTC(), () -> new ScoreActivityContext(
                        "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                        "RELEASE_PUBLISHED", "request-42", "2026-08-06T12:00:00Z")),
                new ScoreActivityFailureClassifier());
        ScoreUser requester = new ScoreUser(
                new UserId(BigInteger.ONE), "developer", "Developer", null, false,
                List.of(ScoreRole.DEVELOPER));
        TransitStateRequest request = new TransitStateRequest();
        request.setReleaseId(new ReleaseId(BigInteger.valueOf(42)));
        request.setState("Published");
        var event = handler.start(new ScoreActivityInvocation(
                "release", "state-change", List.of(requester, request))).succeeded(null).getFirst();

        Path root = Path.of(System.getProperty("user.dir")).getParent();
        String schemaDocument = Files.readString(root.resolve("contracts/score-activity-event.schema.json"));
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaDocument);
        assertThat(schema.validate(mapper.valueToTree(event))).isEmpty();

        var catalog = mapper.readTree(
                Files.readString(root.resolve("contracts/score-activity-event-catalog.json")));
        assertThat(catalog.path("events").findValuesAsText("name"))
                .contains("release.state-change");
    }
}

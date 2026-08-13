package org.oagi.score.gateway.http.api.release_management.service.activity;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityException;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.release_management.controller.payload.ReleaseValidationRequest;
import org.oagi.score.gateway.http.api.release_management.controller.payload.ReleaseValidationResponse;
import org.oagi.score.gateway.http.api.release_management.controller.payload.TransitStateRequest;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.api.release_management.service.ReleaseCommandService;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.math.BigInteger;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseActivityHandlerTest {

    private final ScoreUser requester = new ScoreUser(
            new UserId(BigInteger.valueOf(7)), "developer", "Developer", null, false,
            List.of(ScoreRole.DEVELOPER));
    private final ReleaseActivityHandler handler = new ReleaseActivityHandler(
            new ScoreActivityEventFactory(Clock.systemUTC(), ScoreActivityContext::empty),
            new ScoreActivityFailureClassifier());

    @Test
    void recordsDirectPublishAndDraftRequestsWithTheSameEventShape() {
        ReleaseId releaseId = new ReleaseId(BigInteger.valueOf(42));
        TransitStateRequest publish = new TransitStateRequest();
        publish.setReleaseId(releaseId);
        publish.setState("Published");
        ReleaseValidationRequest draft = new ReleaseValidationRequest();
        draft.setReleaseId(releaseId);

        var published = handler.start(invocation(publish)).succeeded(null).getFirst();
        var drafted = handler.start(invocation(draft))
                .succeeded(new ReleaseValidationResponse()).getFirst();

        assertThat(List.of(published, drafted)).allSatisfy(event -> {
            assertThat(event.name()).isEqualTo("release.state-change");
            assertThat(event.targets()).singleElement().satisfies(target -> {
                assertThat(target.type()).isEqualTo("RELEASE");
                assertThat(target.id()).isEqualTo("42");
            });
        });
        assertThat(published.properties()).containsEntry("toState", "Published");
        assertThat(published.properties()).containsEntry("completionStage", "REQUEST_ACCEPTED");
        assertThat(drafted.properties()).containsEntry("toState", "Draft");
        assertThat(drafted.properties()).containsEntry("completionStage", "REQUEST_ACCEPTED");
    }

    @Test
    void recordsStableFailureCodesWithoutExceptionMessages() {
        TransitStateRequest publish = new TransitStateRequest();
        publish.setReleaseId(new ReleaseId(BigInteger.valueOf(42)));
        publish.setState("Published");

        var event = handler.start(invocation(publish))
                .failed(ScoreActivityException.accessDenied("private details"))
                .getFirst();

        assertThat(event.outcome()).isEqualTo("FAILED");
        assertThat(event.properties())
                .containsEntry("toState", "Published")
                .containsEntry("errorCode", "ACCESS_DENIED")
                .doesNotContainValue("private details");
    }

    @Test
    void bothUiReleaseStateEntryPointsUseTheReleaseHandler() throws Exception {
        ScoreActivity transit = ReleaseCommandService.class
                .getMethod("transitState", ScoreUser.class, TransitStateRequest.class)
                .getAnnotation(ScoreActivity.class);
        ScoreActivity draft = ReleaseCommandService.class
                .getMethod("createDraft", ScoreUser.class, ReleaseValidationRequest.class)
                .getAnnotation(ScoreActivity.class);

        assertThat(List.of(transit, draft)).allSatisfy(annotation -> {
            assertThat(annotation.category()).isEqualTo("release");
            assertThat(annotation.action()).isEqualTo("state-change");
            assertThat(annotation.handler()).isEqualTo(ReleaseActivityHandler.class);
        });
    }

    private ScoreActivityInvocation invocation(Object request) {
        return new ScoreActivityInvocation("release", "state-change", List.of(requester, request));
    }
}

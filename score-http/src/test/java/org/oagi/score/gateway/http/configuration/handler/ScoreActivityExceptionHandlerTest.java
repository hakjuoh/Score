package org.oagi.score.gateway.http.configuration.handler;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityException;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvalidStateException;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreActivityExceptionHandlerTest {

    @Test
    void preservesTheExistingIllegalArgumentHttpContract() {
        ScoreResponseEntityExceptionHandler handler = new ScoreResponseEntityExceptionHandler();
        String message = "The request is invalid.";

        var existingResponse = handler.handleIllegalArgumentException(
                new IllegalArgumentException(message), null);
        var activityResponse = handler.handleScoreActivityException(
                ScoreActivityException.validation(message), null);

        assertThat(activityResponse.getStatusCode()).isEqualTo(existingResponse.getStatusCode());
        assertThat(activityResponse.getBody()).isEqualTo(existingResponse.getBody());
        assertThat(activityResponse.getHeaders()).isEqualTo(existingResponse.getHeaders());
        assertThat(ScoreActivityException.validation(message))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stateMarkerPreservesTheExistingIllegalStateHttpContract() {
        ScoreResponseEntityExceptionHandler handler = new ScoreResponseEntityExceptionHandler();
        String message = "The release cannot be modified.";

        var existingResponse = handler.handleIllegalStateException(
                new IllegalStateException(message), null);
        var activityResponse = handler.handleIllegalStateException(
                new ScoreActivityInvalidStateException(message), null);

        assertThat(activityResponse.getStatusCode()).isEqualTo(existingResponse.getStatusCode());
        assertThat(activityResponse.getBody()).isEqualTo(existingResponse.getBody());
        assertThat(activityResponse.getHeaders()).isEqualTo(existingResponse.getHeaders());
    }
}

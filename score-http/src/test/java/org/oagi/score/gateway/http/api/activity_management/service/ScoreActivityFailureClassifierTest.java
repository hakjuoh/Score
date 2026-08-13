package org.oagi.score.gateway.http.api.activity_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityException;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvalidStateException;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreActivityFailureClassifierTest {

    private final ScoreActivityFailureClassifier classifier = new ScoreActivityFailureClassifier();

    @Test
    void preservesExplicitDomainMeaningBeforeApplyingSafeFallbacks() {
        assertThat(classifier.classify(ScoreActivityException.invalidState("details")))
                .isEqualTo(ScoreActivityFailureCode.INVALID_STATE);
        assertThat(classifier.classify(new AccessDeniedException("details")))
                .isEqualTo(ScoreActivityFailureCode.ACCESS_DENIED);
        assertThat(classifier.classify(new IllegalArgumentException("details")))
                .isEqualTo(ScoreActivityFailureCode.VALIDATION_ERROR);
        assertThat(classifier.classify(new ScoreActivityInvalidStateException("details")))
                .isEqualTo(ScoreActivityFailureCode.INVALID_STATE);
        assertThat(classifier.classify(new IllegalStateException("database unavailable")))
                .isEqualTo(ScoreActivityFailureCode.INTERNAL_ERROR);
        assertThat(classifier.classify(new RuntimeException("details")))
                .isEqualTo(ScoreActivityFailureCode.INTERNAL_ERROR);
    }
}

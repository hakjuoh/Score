package org.oagi.score.gateway.http.api.activity_management.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ScoreActivityEventRecorderTest {

    private final ScoreActivityEventRecorder recorder = new ScoreActivityEventRecorder();

    @Test
    void invokesTheRecordingExactlyOnce() {
        AtomicInteger attempts = new AtomicInteger();

        recorder.record(attempts::incrementAndGet);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void suppressesActivityConstructionOrPublicationFailures() {
        assertThatCode(() -> recorder.record(() -> {
            throw new IllegalStateException("activity unavailable");
        })).doesNotThrowAnyException();
    }

    @Test
    void capturesPreparedStateOrReturnsNullWhenPreparationFails() {
        assertThat(recorder.capture(() -> "prepared")).isEqualTo("prepared");
        Object failedCapture = recorder.capture(() -> {
            throw new IllegalStateException("activity unavailable");
        });
        assertThat(failedCapture).isNull();
    }
}

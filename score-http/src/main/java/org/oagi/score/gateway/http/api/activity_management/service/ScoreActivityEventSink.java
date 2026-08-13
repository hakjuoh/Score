package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;

/**
 * Adapter port for an activity destination such as Redis, Kafka, a database, OpenTelemetry, or a file.
 * Implementations may block because the publisher invokes them on a background worker.
 */
public interface ScoreActivityEventSink {

    String type();

    void write(ScoreActivityEvent event) throws Exception;
}

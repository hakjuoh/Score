package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;

/** Supplies correlation metadata without coupling event producers to a tracing implementation. */
public interface ScoreActivityContextProvider {

    ScoreActivityContext currentContext();
}

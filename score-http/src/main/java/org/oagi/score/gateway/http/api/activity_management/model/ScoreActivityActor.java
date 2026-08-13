package org.oagi.score.gateway.http.api.activity_management.model;

import org.oagi.score.gateway.http.common.model.ScoreUser;

import static java.util.Objects.requireNonNull;

/** The SCORE user that initiated an activity. */
public record ScoreActivityActor(String userId, String loginId) {

    public ScoreActivityActor {
        requireNonNull(userId, "userId must not be null");
        requireNonNull(loginId, "loginId must not be null");
    }

    public static ScoreActivityActor from(ScoreUser user) {
        requireNonNull(user, "user must not be null");
        return new ScoreActivityActor(user.userId().toString(), user.username());
    }
}

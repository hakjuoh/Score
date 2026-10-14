package org.oagi.score.gateway.http.api.ai_management.repository.jooq;

import org.jooq.JSON;

/** Reads the text of a jOOQ JSON column value; the writing side is {@link JSON#jsonOrNull(String)}. */
public final class AiJsonColumns {

    private AiJsonColumns() {
    }

    public static String text(JSON json) {
        return json != null ? json.data() : null;
    }
}

package org.oagi.score.gateway.http.api.release_management.model.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.release_management.model.ReleaseId;
import org.oagi.score.gateway.http.common.model.event.Event;

import java.util.Map;

@Data
@EqualsAndHashCode(exclude = "traceContext")
@NoArgsConstructor
@AllArgsConstructor
public class ReleaseCleanupEvent implements Event {

    private UserId userId;
    private ReleaseId releaseId;
    private Map<String, String> traceContext;

    public ReleaseCleanupEvent(UserId userId, ReleaseId releaseId) {
        this(userId, releaseId, Map.of());
    }

    public Map<String, String> getTraceContext() {
        return traceContext == null ? Map.of() : Map.copyOf(traceContext);
    }

}

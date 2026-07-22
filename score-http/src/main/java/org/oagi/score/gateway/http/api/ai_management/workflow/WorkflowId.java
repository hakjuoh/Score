package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.Locale;
import java.util.Objects;

public record WorkflowId(String value) {
    public static final WorkflowId DIRECT = new WorkflowId("direct");
    public WorkflowId {
        value = Objects.requireNonNull(value, "workflow id").strip()
                .toLowerCase(Locale.ROOT).replace('-', '_');
        if (!value.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("Invalid Workflow id: " + value);
        }
    }
}

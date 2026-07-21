package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.List;

@FunctionalInterface
public interface WorkflowAggregator {

    WorkflowResult aggregate(WorkflowContext context, List<WorkflowResult> results);
}

package org.oagi.score.gateway.http.api.ai_management.workflow;

/** A synchronous, composable unit of AI work. */
public interface Workflow {

    String id();

    WorkflowResult process(WorkflowContext context);
}

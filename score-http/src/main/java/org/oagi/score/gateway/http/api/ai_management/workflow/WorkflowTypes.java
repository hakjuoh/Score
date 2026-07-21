package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.Set;

/**
 * The single source of truth for model-authorable workflow type names. The planner
 * grammar, node validation, compiler registry, and execution dispatch must all
 * reference these constants so a new type is added in exactly one place per concern.
 */
public final class WorkflowTypes {

    public static final String DIRECT = "direct";
    public static final String CHAIN = "chain";
    public static final String PARALLEL = "parallel";
    public static final String ROUTING = "routing";
    public static final String ORCHESTRATOR_WORKERS = "orchestrator_workers";

    public static final Set<String> ALL = Set.of(
            DIRECT, CHAIN, PARALLEL, ROUTING, ORCHESTRATOR_WORKERS);

    private WorkflowTypes() {
    }
}

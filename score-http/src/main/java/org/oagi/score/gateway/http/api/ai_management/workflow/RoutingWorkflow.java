package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Selects exactly one named child workflow for the current input. */
public final class RoutingWorkflow implements Workflow {

    private final String id;
    private final Function<WorkflowContext, String> routeSelector;
    private final Map<String, Workflow> routes;

    public RoutingWorkflow(String id, Function<WorkflowContext, String> routeSelector,
                           Map<String, Workflow> routes) {
        this.id = Objects.requireNonNull(id, "id");
        this.routeSelector = Objects.requireNonNull(routeSelector, "routeSelector");
        this.routes = routes != null
                ? Map.copyOf(new LinkedHashMap<>(routes)) : Map.of();
        if (this.routes.isEmpty()) {
            throw new IllegalArgumentException("A routing workflow requires at least one route.");
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WorkflowResult process(WorkflowContext context) {
        Objects.requireNonNull(context, "context");
        String selected = routeSelector.apply(context);
        Workflow route = routes.get(selected);
        if (route == null) {
            throw new IllegalArgumentException("Unknown workflow route '" + selected + "'.");
        }
        WorkflowResult result = Objects.requireNonNull(route.process(context),
                () -> "Workflow route '" + selected + "' returned null.");
        if (!result.successful()) {
            throw result.failure();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("workflow", "routing");
        metadata.put("selected_route", selected);
        return WorkflowResult.success(id, result.output(),
                metadata, List.of(result));
    }
}

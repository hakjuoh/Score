package org.oagi.score.gateway.http.api.ai_management.workflow;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Global source of installed advanced Workflow capabilities; direct is always present. */
public final class WorkflowExecutorRegistry {

    private final Map<WorkflowId, Registration> registrations;

    public WorkflowExecutorRegistry(Collection<? extends Registration> registrations) {
        Map<WorkflowId, Registration> indexed = new LinkedHashMap<>();
        indexed.put(WorkflowId.DIRECT, new Registration() {
            @Override public WorkflowId workflowId() { return WorkflowId.DIRECT; }
            @Override public String description() { return "Direct Agent execution"; }
        });
        if (registrations != null) {
            for (Registration registration : registrations) {
                Objects.requireNonNull(registration, "workflow registration");
                if (WorkflowId.DIRECT.equals(registration.workflowId())) continue;
                if (indexed.putIfAbsent(registration.workflowId(), registration) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate Workflow registration: " + registration.workflowId().value());
                }
            }
        }
        this.registrations = Map.copyOf(indexed);
    }

    public Optional<Registration> find(WorkflowId workflowId) {
        return Optional.ofNullable(registrations.get(workflowId));
    }

    public Set<WorkflowId> installed() { return registrations.keySet(); }

    public void requireInstalled(String workflowId) {
        WorkflowId id = new WorkflowId(workflowId);
        if (!registrations.containsKey(id)) {
            throw new IllegalArgumentException("The requested Workflow is not installed: " + id.value());
        }
    }

    public interface Registration {
        WorkflowId workflowId();
        String description();
    }
}

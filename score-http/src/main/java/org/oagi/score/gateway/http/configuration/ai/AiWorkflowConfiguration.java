package org.oagi.score.gateway.http.configuration.ai;

import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowExecutorRegistry;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowId;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class AiWorkflowConfiguration {

    @Bean
    public WorkflowExecutorRegistry workflowExecutorRegistry() {
        return new WorkflowExecutorRegistry(List.of(
                registration(WorkflowTypes.CHAIN, "Sequential Agent chain"),
                registration(WorkflowTypes.PARALLEL, "Parallel Agent fan-out"),
                registration(WorkflowTypes.ROUTING, "Conditional Agent routing"),
                registration(WorkflowTypes.ORCHESTRATOR_WORKERS, "Orchestrator and worker Agents")));
    }

    private WorkflowExecutorRegistry.Registration registration(String id, String description) {
        return new WorkflowExecutorRegistry.Registration() {
            @Override public WorkflowId workflowId() { return new WorkflowId(id); }
            @Override public String description() { return description; }
        };
    }
}

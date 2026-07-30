package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.DelegationIntent;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** Adapts an accepted transport turn to the protocol-neutral Workflow request contract. */
public final class WorkflowRequestAdapter {

    private WorkflowRequestAdapter() { }

    public static AgentWorkflowContext.Request from(ChatExecutionContext context) {
        Objects.requireNonNull(context, "context");
        var request = context.request();
        var accepted = context.userMessage();
        String requesterId = context.requesterId();
        int maximumAgents = request.multiAgent() != null
                ? request.multiAgent().maxAgents() : 1;
        String strategy = request.multiAgent() != null
                ? request.multiAgent().strategy() : "balanced";
        boolean delegation = request.changeConfirmation() == null
                && (request.multiAgent() != null && request.multiAgent().active()
                || StringUtils.hasText(request.activeWorkflow())
                && !"assistant".equalsIgnoreCase(request.activeWorkflow().strip())
                && !"direct".equalsIgnoreCase(request.activeWorkflow().strip()));
        boolean explicitDelegation = request.changeConfirmation() == null
                && DelegationIntent.explicitlyRequestsAgents(
                        accepted != null ? accepted.content() : request.prompt());
        return new AgentWorkflowContext.Request(request.requestId(),
                request.conversationId(), requesterId,
                StringUtils.hasText(request.modelName()) ? request.modelName() : "unknown",
                accepted != null ? Objects.requireNonNullElse(accepted.content(), "")
                        : Objects.requireNonNullElse(request.prompt(), ""),
                accepted != null && accepted.attachments() != null
                        ? !accepted.attachments().isEmpty()
                        : request.attachments() != null && !request.attachments().isEmpty(),
                StringUtils.hasText(request.pageContext()), maximumAgents,
                strategy, request.activeWorkflow(), delegation,
                explicitDelegation,
                request.changeConfirmation() != null);
    }
}

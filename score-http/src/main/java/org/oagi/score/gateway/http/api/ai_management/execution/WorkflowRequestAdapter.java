package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** Adapts an accepted transport turn to the protocol-neutral Workflow request contract. */
public final class WorkflowRequestAdapter {

    private WorkflowRequestAdapter() { }

    public static AgentWorkflowContext.Request from(AiChatExecutor.Context context) {
        var request = context.request();
        var accepted = context.userMessage();
        String requesterId = context.requester() != null && context.requester().userId() != null
                ? context.requester().userId().value().toString()
                : context.requester() != null && StringUtils.hasText(context.requester().username())
                ? context.requester().username() : "unknown";
        int maximumAgents = request.multiAgent() != null
                ? request.multiAgent().maxAgents() : 1;
        String strategy = request.multiAgent() != null
                ? request.multiAgent().strategy() : "balanced";
        boolean delegation = request.mutationConfirmation() == null
                && (request.multiAgent() != null && request.multiAgent().active()
                || StringUtils.hasText(request.activeWorkflow())
                && !"assistant".equalsIgnoreCase(request.activeWorkflow().strip())
                && !"direct".equalsIgnoreCase(request.activeWorkflow().strip()));
        return new AgentWorkflowContext.Request(request.requestId(),
                request.conversationId(), requesterId,
                StringUtils.hasText(request.modelName()) ? request.modelName() : "unknown",
                accepted != null ? Objects.requireNonNullElse(accepted.getText(), "")
                        : Objects.requireNonNullElse(request.prompt(), ""),
                accepted != null && accepted.getMedia() != null
                        ? !accepted.getMedia().isEmpty()
                        : request.attachments() != null && !request.attachments().isEmpty(),
                StringUtils.hasText(request.pageContext()), maximumAgents,
                strategy, request.activeWorkflow(), delegation,
                request.mutationConfirmation() != null);
    }
}

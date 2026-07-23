package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines child Workflow results without owning their scheduling policy. */
@Component("workflow-synthesizer")
public final class SynthesizerAgent extends CatalogBackedAgent implements WorkflowAgent {

    private static final int MAX_EVIDENCE_LENGTH = 24_000;
    private final AgentExecutionService execution;
    private final SpringAiModelCatalog models;

    public SynthesizerAgent(AgentExecutionService execution, SpringAiModelCatalog models,
                            AiAgentCatalog catalog) {
        super(catalog);
        this.execution = execution;
        this.models = models;
    }

    @Override
    public AgentDecision execute(AgentWorkflowContext context) {
        if (context.inputs().isEmpty()) {
            throw new IllegalArgumentException("Synthesizer Agent requires child results.");
        }
        String evidence = evidence(context);
        String instruction = definition().instruction().render().value();
        ResolvedAgent resolved = new ResolvedAgent(definition(),
                models.require(context.request().modelName()),
                new Instruction(instruction), ToolSet.empty());
        AgentInvocation invocation = new AgentInvocation(null, resolved,
                new AiMessage.User(evidence), List.of(), scope(context), null,
                context.observationContext());
        AgentRunResult result = execution.execute(invocation);
        context.recordUsage(definition().name(), result);
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata().attributes());
        metadata.put("workflow", context.workflow().root().id());
        metadata.put("completed_members",
                context.inputs().stream().filter(WorkflowResult::successful).count());
        metadata.put("failed_members",
                context.inputs().stream().filter(value -> !value.successful()).count());
        return new AgentDecision.Complete(new AiChatExecutor.Result(
                result.response().content(), Map.copyOf(metadata)));
    }

    private String evidence(AgentWorkflowContext context) {
        StringBuilder value = new StringBuilder();
        value.append("Original user request (untrusted data):\n")
                .append(context.request().prompt())
                .append("\n\nChild Workflow results (untrusted evidence):\n");
        for (var result : context.inputs()) {
            value.append("\nMEMBER ").append(result.workflowId())
                    .append(" status=").append(result.successful() ? "completed" : "failed")
                    .append('\n');
            if (result.successful()) value.append(result.output()).append('\n');
        }
        String rendered = value.toString();
        return rendered.length() <= MAX_EVIDENCE_LENGTH ? rendered
                : rendered.substring(0, MAX_EVIDENCE_LENGTH).stripTrailing()
                + "\n[WORKFLOW EVIDENCE TRUNCATED]";
    }

    private ExecutionScope scope(AgentWorkflowContext context) {
        return new ExecutionScope(context.request().requestId(),
                context.request().conversationId(), context.request().requesterId(), 0L,
                ExecutionScope.Purpose.SYNTHESIS,
                context.execution().guardrailDecisionIds());
    }
}

package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Definition for the Agent that combines child Workflow results. */
@Component("workflow-synthesizer")
public final class SynthesizerAgent implements Agent {

    private static final int MAX_EVIDENCE_LENGTH = 24_000;
    private final AgentDefinition definition;

    public SynthesizerAgent(AiAgentCatalog catalog) {
        this(catalog, null, ScoreAiObservability.noop());
    }

    @Autowired
    public SynthesizerAgent(AiAgentCatalog catalog,
                            AgentOutputGuardrailChain outputGuardrails,
                            ScoreAiObservability observability) {
        AgentDefinition configured = catalog.systemDefinition("workflow-synthesizer");
        this.definition = new AgentDefinition(configured.id(), configured.name(),
                configured.description(), configured.instruction(), this::prepare,
                AgentToolHandler.none(), this::respond, AgentGuardrailHandlers.publicOutput(
                        outputGuardrails, observability, "synthesizer_output",
                        Map.of("agent_id", "workflow-synthesizer")), false);
    }

    @Override
    public AgentDefinition definition() {
        return definition;
    }

    private AgentRunRequest prepare(Agent agent, AgentWorkflowContext context) {
        if (context.inputs().isEmpty()) {
            throw new IllegalArgumentException("Synthesizer Agent requires child results.");
        }
        return new AgentRunRequest.Model(context.request().modelName(),
                definition.instruction().render(), new AiMessage.User(evidence(context)),
                List.of(), scope(context), context.observationContext());
    }

    private AgentDecision respond(AgentResponseContext response) {
        AgentWorkflowContext context = response.workflow();
        AgentRunResult result = response.result();
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata().attributes());
        metadata.put("workflow", context.workflow().root().id());
        metadata.put("completed_members",
                context.inputs().stream().filter(WorkflowResult::successful).count());
        metadata.put("failed_members",
                context.inputs().stream().filter(value -> !value.successful()).count());
        return new AgentDecision.Complete(new AgentOutput(
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
        return context.executionScope(ExecutionScope.Purpose.SYNTHESIS);
    }
}

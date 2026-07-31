package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutputRetryHandoffException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.execution.WorkflowRequestAdapter;
import org.oagi.score.gateway.http.api.ai_management.model.AiCompactCommand;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiPersistentWorkflowCommand;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowRunner;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Selects the configured workflow/compaction path and returns immutable execution state. */
final class ChatTurnExecutor {

    private final ChatPromptAssembler prompts;
    private final WorkflowRunner workflow;
    private final org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService
            contextBudgets;
    private final ConversationCompactionSupport compactions;
    private final ChatOutputDiscloser outputDiscloser;

    ChatTurnExecutor(ChatPromptAssembler prompts, WorkflowRunner workflow,
                     org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService
                             contextBudgets,
                     ConversationCompactionSupport compactions,
                     ChatOutputDiscloser outputDiscloser) {
        this.prompts = prompts;
        this.workflow = workflow;
        this.contextBudgets = contextBudgets;
        this.compactions = compactions;
        this.outputDiscloser = outputDiscloser;
    }

    ChatTurnExecution execute(ChatRequest request, List<Message> initialHistory,
                              UserMessage userMessage, ScoreUser requester,
                              AiTrajectoryRecorder recorder, ExecutionScope scope,
                              Optional<AiContextBudget> budget, long projectedInputTokens,
                              AiCompactCommand compactCommand,
                              Optional<AiPersistentWorkflowCommand> workflowCommand,
                              Consumer<String> progress) {
        if (workflowCommand.isPresent()) {
            AiPersistentWorkflowCommand command = workflowCommand.orElseThrow();
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("workflow_preference", true);
            if (command.activeWorkflow() != null) {
                notice.put("active_workflow", command.activeWorkflow());
            }
            recorder.guide(command.notice(), Map.copyOf(notice));
            Map<String, Object> metadata = Map.of("workflow", "configuration",
                    "active_workflow", Objects.toString(
                            request.activeWorkflow(), "automatic"));
            return result(new AgentOutput(command.acknowledgement(), metadata), initialHistory);
        }
        if (compactCommand != null) {
            progress.accept("Compacting the conversation context.");
            AgentOutput summary = compactions.executeSummary(request, initialHistory,
                    userMessage, requester, recorder, true, scope);
            return result(summary, initialHistory);
        }

        List<Message> history = initialHistory;
        ChatAutomaticCompaction automatic = ChatAutomaticCompaction.none();
        if (!history.isEmpty() && budget.isPresent()
                && budget.get().shouldCompact(projectedInputTokens)) {
            progress.accept("Compacting the conversation context before continuing.");
            long beforeTokens = projectedInputTokens;
            String summary = compactions.executeSummary(request, history,
                    prompts.compactMessage(null), requester, recorder, false, scope).content();
            history = List.of(compactions.summaryMessage(summary));
            projectedInputTokens = contextBudgets.estimateInputTokens(
                    history, userMessage, request.pageContext());
            recorder.resetEstimatedInputFloor(projectedInputTokens);
            automatic = new ChatAutomaticCompaction(
                    summary, beforeTokens, projectedInputTokens);
        }
        if (budget.isPresent() && budget.get().exceedsSafeInput(projectedInputTokens)) {
            throw new IllegalArgumentException(
                    "The request is too large for the selected model's safe context budget.");
        }
        return new ChatTurnExecution(executeWorkflow(
                request, history, userMessage, requester, recorder, scope), history, automatic);
    }

    private ChatTurnExecution result(AgentOutput output, List<Message> history) {
        return new ChatTurnExecution(ChatTurnOutput.disclosable(output), history,
                ChatAutomaticCompaction.none());
    }

    private ChatTurnOutput executeWorkflow(ChatRequest request, List<Message> history,
                                           UserMessage userMessage, ScoreUser requester,
                                           AiTrajectoryRecorder recorder,
                                           ExecutionScope scope) {
        try {
            ChatExecutionContext execution = ChatExecutionContext.fromRequest(
                            request, history, userMessage, requester, recorder, true, true)
                    .withGuardrailDecisions(scope.guardrailDecisionIds());
            AgentWorkflowContext workflowContext = AgentWorkflowContext.root(execution,
                    WorkflowRequestAdapter.from(execution),
                    Math.max(1, workflow.maximumIterations()));
            return ChatTurnOutput.disclosable(workflow.execute(workflowContext));
        } catch (AgentOutputRetryHandoffException handoff) {
            Map<String, Object> metadata = new LinkedHashMap<>(handoff.metadata());
            metadata.put("agentId", handoff.agentId().value());
            metadata.put("agent_output_retry_handoff", true);
            return new ChatTurnOutput(handoff.candidate(), Map.copyOf(metadata),
                    handoff.feedback(), false,
                    new AgentOutput(handoff.candidate(), Map.copyOf(metadata)));
        } catch (AgentGuardrailRefusedException refused) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("finish_reason", "GUARDRAIL_REFUSAL");
            metadata.put("model_input_guardrail_decision",
                    refused.refusal().decision().decisionId());
            refused.agentId().ifPresent(id -> metadata.put("agentId", id.value()));
            String answer = outputDiscloser.publicGuardrailMessage(refused.refusal());
            return new ChatTurnOutput(answer, Map.copyOf(metadata), null, true,
                    new AgentOutput(answer, Map.copyOf(metadata)));
        }
    }
}

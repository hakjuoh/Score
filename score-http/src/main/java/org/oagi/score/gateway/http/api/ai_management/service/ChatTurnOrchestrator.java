package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatResponse;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiUserMessageAdapter;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangePermissionMode;
import org.oagi.score.gateway.http.api.ai_management.model.AiChatTrajectoryStep;
import org.oagi.score.gateway.http.api.ai_management.model.AiCompactCommand;
import org.oagi.score.gateway.http.api.ai_management.model.AiContextBudget;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.model.AiPersistentWorkflowCommand;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileDescriptor;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowIntent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentIdentityProvider;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Executes one prepared assistant turn from input policy through durable disclosure. */
final class ChatTurnOrchestrator {

    private final AgentIdentityProvider rootAgentIdentity;
    private final ChatPromptAssembler prompts;
    private final AiConversationUseCases conversations;
    private final ObjectMapper objectMapper;
    private final AiRequestRegistry requests;
    private final AiContextBudgetService contextBudgets;
    private final AgentInputGuardrailChain inputGuardrails;
    private final ScoreAiObservability observability;
    private final ExecutionObserver observer;
    private final ChatOutputDiscloser outputDiscloser;
    private final AiFileService files;
    private final ConversationCompactionSupport compactions;
    private final ChatConversationJournal journal;
    private final ChatTurnExecutor executor;
    private final ChatTurnCommitter committer;

    ChatTurnOrchestrator(AgentIdentityProvider rootAgentIdentity,
                         ChatPromptAssembler prompts,
                         AiConversationUseCases conversations,
                         ObjectMapper objectMapper, AiRequestRegistry requests,
                         AiContextBudgetService contextBudgets,
                         AgentInputGuardrailChain inputGuardrails,
                         ScoreAiObservability observability, ExecutionObserver observer,
                         AiFileService files, ConversationCompactionSupport compactions,
                         ChatConversationJournal journal,
                         ChatOutputDiscloser outputDiscloser,
                         ChatTurnExecutor executor, ChatTurnCommitter committer) {
        this.rootAgentIdentity = rootAgentIdentity;
        this.prompts = prompts;
        this.conversations = conversations;
        this.objectMapper = objectMapper;
        this.requests = requests;
        this.contextBudgets = contextBudgets;
        this.inputGuardrails = inputGuardrails;
        this.observability = observability;
        this.observer = observer;
        this.outputDiscloser = outputDiscloser;
        this.files = files;
        this.compactions = compactions;
        this.journal = journal;
        this.executor = executor;
        this.committer = committer;
    }

    ChatResponse chat(ChatRequest request, ScoreUser requester,
                      Consumer<AiExecutionEvent> progress, long generation) {
        ChatRequest prepared = prompts.requirePrepared(request);
        AiChatConversationRepository repository = conversations.repository(requester);
        List<String> progressMessages = new ArrayList<>();
        AiCompactCommand compactCommand = prompts.compactCommand(prepared.prompt());
        boolean manualCompact = compactCommand != null;
        Optional<AiPersistentWorkflowCommand> workflowCommand =
                AiWorkflowIntent.persistentWorkflowCommand(prepared.prompt());
        UserMessage userMessage = manualCompact
                ? prompts.compactMessage(compactCommand.instructions())
                : prompts.userMessage(prepared);
        ExecutionScope turnScope = ChatExecutionScopes.turn(prepared, requester, generation);
        List<GuardrailDecision> turnDecisions = List.of();
        if (inputGuardrails != null) {
            AgentInputGuardrailChain.Outcome guarded = inputGuardrails.evaluate(
                    new AgentInputGuardrail.Request(AgentInputGuardrail.Scope.TURN_LOCAL,
                            SpringAiUserMessageAdapter.toCore(userMessage), List.of(), turnScope,
                            Map.of("attachment_count", prepared.attachments().size())));
            turnDecisions = guarded.decisions();
            observability.recordGuardrails(prepared.requestId(), "turn_input",
                    turnDecisions, guarded.refusal());
            if (!guarded.allowed()) {
                return commitGuardrailRefusal(
                        prepared, requester, guarded.refusal(), turnDecisions, turnScope);
            }
            userMessage = SpringAiUserMessageAdapter.toSpring(guarded.input());
            turnScope = ChatExecutionScopes.withDecisions(turnScope, turnDecisions);
        }

        final UserMessage acceptedUserMessage = userMessage;
        List<Message> initialHistory = conversations.history(
                requester, prepared.conversationId());
        Optional<AiContextBudget> budget = contextBudgets.budget(prepared.modelName());
        long projectedInputTokens = compactions.projectedInputTokens(
                requester, prepared, initialHistory, acceptedUserMessage, budget);
        long initialProjectedInputTokens = projectedInputTokens;
        Map<String, Object> traceContext = observability.correlation(prepared.requestId());
        AiTrajectoryRecorder recorder = new AiTrajectoryRecorder(repository, objectMapper,
                requester, prepared.conversationId(), prepared.requestId(), prepared.modelName(),
                prepared.reasoningEffort(), progress, budget.orElse(null), projectedInputTokens,
                traceContext, turnScope, observer, observability);
        budget.ifPresent(value -> recorder.contextUsage(value.usage(
                initialProjectedInputTokens, true, "preflight_estimate")));
        Consumer<String> collectingProgress = message -> {
            progressMessages.add(message);
            recorder.progress(message);
        };

        String permissionMode = AiChangePermissionMode.resolve(
                prepared.permissionMode()).value();
        recorder.recordUserMessage(prompts.visiblePrompt(prepared), projectionMetadata(
                traceContext, permissionMode, prepared));
        ChatTurnExecution execution = executor.execute(prepared, initialHistory,
                acceptedUserMessage, requester, recorder, turnScope, budget,
                projectedInputTokens, compactCommand, workflowCommand, collectingProgress);
        ChatTurnOutput output = execution.output();
        rejectDiscarded(prepared.requestId());
        if (!StringUtils.hasText(output.answer())) {
            throw new IllegalStateException("The assistant returned an empty response.");
        }
        ChatDisclosure disclosure = outputDiscloser.disclose(output, prepared, execution.history(),
                acceptedUserMessage, requester, recorder, turnScope);
        String safeAnswer = disclosure.answer();
        String tracedAgentId = Objects.toString(
                disclosure.metadata().get("agentId"), null);
        String responseAgentId = StringUtils.hasText(tracedAgentId)
                ? tracedAgentId : rootAgentIdentity.rootAgentId();
        recorder.contentDelta(safeAnswer);
        Map<String, Object> committedTrace = outputDiscloser.committedTrace(
                disclosure, responseAgentId);

        committer.commit(new ChatTurnCommitter.Command(prepared, requester, repository,
                acceptedUserMessage, safeAnswer, committedTrace, traceContext, permissionMode,
                budget, manualCompact, initialProjectedInputTokens,
                execution.automaticCompaction(), generation, recorder));
        List<AiFileDescriptor> createdFiles = files != null
                ? files.ensureRequestedFiles(requester, turnScope,
                prepared.prompt(), safeAnswer) : List.of();
        return new ChatResponse(responseAgentId, safeAnswer, prepared.conversationId(),
                false, List.copyOf(progressMessages), createdFiles, List.of());
    }

    private ChatResponse commitGuardrailRefusal(ChatRequest request, ScoreUser requester,
                                                GuardrailRefusal refusal,
                                                List<GuardrailDecision> decisions,
                                                ExecutionScope turnScope) {
        String answer = outputDiscloser.publicGuardrailMessage(refusal);
        AiChatConversationRepository repository = conversations.repository(requester);
        Runnable persistence = () -> {
            ChatMemory memory = conversations.memory(requester);
            if (memory != null) {
                memory.add(request.conversationId(), new AssistantMessage(answer));
            }
            Map<String, Object> metadata = new LinkedHashMap<>(
                    observability.correlation(request.requestId()));
            metadata.put("ui_projection", true);
            metadata.put("finish_reason", "GUARDRAIL_REFUSAL");
            metadata.put("decision_id", refusal.decision().decisionId());
            metadata.put("policy_version", refusal.decision().policyVersion());
            metadata.put("retention", refusal.decision().retention().name());
            metadata.put("guardrail_decisions", decisions.stream()
                    .map(GuardrailDecision::decisionId).toList());
            journal.append(requester, repository, request.conversationId(),
                    new AiChatTrajectoryStep(request.requestId(), "system",
                            "guardrail_refusal", "visible", answer, null,
                            request.modelName(), request.reasoningEffort(), null, null, null,
                            Map.copyOf(metadata), 0, null, null),
                    ExecutionScope.Purpose.GUARDRAIL_EVALUATION,
                    Map.of("decision_id", refusal.decision().decisionId(),
                            "outcome", "refused"),
                    () -> repository.markExpanded(request.conversationId()),
                    turnScope != null ? turnScope.generation() : 0L);
        };
        committer.commitResult(request.requestId(), persistence);
        return new ChatResponse(rootAgentIdentity.rootAgentId(), answer,
                request.conversationId(), false, List.of());
    }

    private Map<String, Object> projectionMetadata(Map<String, Object> traceContext,
                                                   String permissionMode,
                                                   ChatRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(traceContext);
        metadata.put("ui_projection", true);
        metadata.put("permission_mode", permissionMode);
        metadata.put("multi_agent", request.multiAgent().asMap());
        if (request.activeWorkflow() != null) {
            metadata.put("active_workflow", request.activeWorkflow());
        }
        return Map.copyOf(metadata);
    }

    private void rejectDiscarded(String requestId) {
        if (Thread.currentThread().isInterrupted()
                || requests != null && requests.shouldDiscardResult(requestId)) {
            throw new CancellationException("The assistant request was interrupted.");
        }
    }

}

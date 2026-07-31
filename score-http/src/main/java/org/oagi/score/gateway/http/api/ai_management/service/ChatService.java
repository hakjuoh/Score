package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentIdentityProvider;
import org.oagi.score.gateway.http.api.ai_management.agent.ResponseOnlyAgent;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChatModelInfo;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiConversationModelResponse;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationDetails;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatConversationSummary;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatResponse;
import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationCompactor;
import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationResultCommitter;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.memory.ScoreChatMemoryFactory;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatConversationRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AtifTrajectoryService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.TrajectoryStepAppender;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowRunner;
import org.oagi.score.gateway.http.api.info_management.model.AiAssistantInfoRecord;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Transactional facade for assistant turns and conversation administration. */
@Service
public class ChatService {

    private final ScoreAiModelRegistry models;
    private final AgentIdentityProvider rootAgentIdentity;
    private final AiConversationUseCases conversations;
    private final ConversationSettingsManager settings;
    private final ChatTurnOrchestrator turns;
    private final ChatConversationJournal journal;

    @Autowired
    public ChatService(ScoreAiModelRegistry models, AgentRunner agentRunner,
                       ToolSearchToolCallingAdvisor toolSearchAdvisor,
                       ScoreChatMemoryFactory chatMemoryFactory,
                       RepositoryFactory repositoryFactory, ObjectMapper objectMapper,
                       AiRequestRegistry requests, AiContextBudgetService contextBudgets,
                       WorkflowRunner workflow, AtifTrajectoryService atifTrajectoryService,
                       AgentInputGuardrailChain inputGuardrails,
                       AgentOutputGuardrailChain outputGuardrails,
                       ConversationResultCommitter resultCommitter,
                       ConversationCompactor compactor,
                       ResponseOnlyAgent responseOnlyAgent, AiFileService files,
                       ScoreAiObservability observability,
                       ObjectProvider<ExecutionObserver> executionObservers) {
        this(models, new Dependencies(agentRunner, toolSearchAdvisor,
                chatMemoryFactory::create,
                requester -> repositoryFactory.aiChatConversationRepository(
                        requester, AiChatJsonSerializer.getInstance()),
                objectMapper, requests, contextBudgets, workflow, agentRunner,
                atifTrajectoryService, inputGuardrails, outputGuardrails, resultCommitter,
                compactor, responseOnlyAgent, files, observability,
                executionObservers != null
                        ? executionObservers.getIfAvailable(ExecutionObserver::noop)
                        : ExecutionObserver.noop()));
    }

    ChatService(ScoreAiModelRegistry models, Dependencies dependencies) {
        Dependencies value = Objects.requireNonNull(dependencies);
        this.models = Objects.requireNonNull(models);
        this.rootAgentIdentity = value.rootAgentIdentity();
        ScoreAiObservability observability = value.observability() != null
                ? value.observability() : ScoreAiObservability.noop();
        ExecutionObserver observer = value.observer() != null
                ? value.observer() : ExecutionObserver.noop();
        ChatPromptAssembler prompts = new ChatPromptAssembler(models, value.objectMapper());
        this.conversations = new AiConversationUseCases(models, value.chatMemories(),
                value.conversationRepositories(), value.contextBudgets(),
                value.atifTrajectoryService(), value.toolSearchAdvisor(), value.files());
        this.journal = new ChatConversationJournal(value.conversationRepositories(),
                observability, new TrajectoryStepAppender(observer));
        StandaloneAgentExecutor standaloneAgents = new StandaloneAgentExecutor(
                value.agentRunner(), value.requests());
        ConversationCompactionSupport compactions = new ConversationCompactionSupport(conversations,
                value.contextBudgets(), value.compactor(), value.outputGuardrails(),
                observability, standaloneAgents);
        ChatOutputDiscloser outputDiscloser = new ChatOutputDiscloser(
                value.outputGuardrails(), value.responseOnlyAgent(), observability,
                standaloneAgents);
        ChatTurnExecutor turnExecutor = new ChatTurnExecutor(prompts, value.workflow(),
                value.contextBudgets(), compactions, outputDiscloser);
        ChatTurnCommitter turnCommitter = new ChatTurnCommitter(conversations, compactions,
                journal, value.contextBudgets(), value.resultCommitter(), value.requests());
        this.settings = new ConversationSettingsManager(models, prompts, conversations,
                value.contextBudgets(), compactions, journal, value.objectMapper(),
                observability, observer);
        this.turns = new ChatTurnOrchestrator(value.rootAgentIdentity(), prompts,
                conversations, value.objectMapper(), value.requests(), value.contextBudgets(),
                value.inputGuardrails(), observability, observer, value.files(), compactions,
                journal, outputDiscloser, turnExecutor, turnCommitter);
    }

    /** Cohesive runtime collaborators; tests customize this value instead of constructors. */
    record Dependencies(
            AgentIdentityProvider rootAgentIdentity,
            ToolSearchToolCallingAdvisor toolSearchAdvisor,
            Function<ScoreUser, ChatMemory> chatMemories,
            Function<ScoreUser, AiChatConversationRepository> conversationRepositories,
            ObjectMapper objectMapper,
            AiRequestRegistry requests,
            AiContextBudgetService contextBudgets,
            WorkflowRunner workflow,
            AgentRunner agentRunner,
            AtifTrajectoryService atifTrajectoryService,
            AgentInputGuardrailChain inputGuardrails,
            AgentOutputGuardrailChain outputGuardrails,
            ConversationResultCommitter resultCommitter,
            ConversationCompactor compactor,
            ResponseOnlyAgent responseOnlyAgent,
            AiFileService files,
            ScoreAiObservability observability,
            ExecutionObserver observer) {
    }

    public AiAssistantInfoRecord aiAssistantInfo() {
        return models.isAvailable()
                ? new AiAssistantInfoRecord(true, null)
                : new AiAssistantInfoRecord(false, "AI_MODEL_NOT_CONFIGURED");
    }

    @Transactional
    public ChatRequest prepare(ChatRequest request, ScoreUser requester) {
        return prepare(request, requester, 0L);
    }

    @Transactional
    public ChatRequest prepare(ChatRequest request, ScoreUser requester,
                               long requestGeneration) {
        return settings.prepare(request, requester, requestGeneration);
    }

    public ChatResponse chat(ChatRequest request, ScoreUser requester,
                             Consumer<AiExecutionEvent> progress) {
        return chat(request, requester, progress, 0L);
    }

    public ChatResponse chat(ChatRequest request, ScoreUser requester,
                             Consumer<AiExecutionEvent> progress,
                             long requestGeneration) {
        return turns.chat(request, requester, progress, requestGeneration);
    }

    public String rootAgentId() {
        return rootAgentIdentity.rootAgentId();
    }

    @Transactional(readOnly = true)
    public List<ChatConversationSummary> conversations(ScoreUser requester) {
        return conversations.list(requester);
    }

    @Transactional(readOnly = true)
    public ChatConversationDetails conversation(ScoreUser requester,
                                                String conversationId) {
        return conversations.get(requester, conversationId);
    }

    public List<AiChatModelInfo> availableModels() {
        return conversations.availableModels();
    }

    @Transactional
    public AiConversationModelResponse updateConversationModel(
            ScoreUser requester, String conversationId, String requestedModelName,
            String requestedReasoningEffort) {
        return updateConversationModel(requester, conversationId, requestedModelName,
                requestedReasoningEffort, null, null);
    }

    @Transactional
    public AiConversationModelResponse updateConversationModel(
            ScoreUser requester, String conversationId, String requestedModelName,
            String requestedReasoningEffort, String traceparent, String tracestate) {
        return settings.update(requester, conversationId, requestedModelName,
                requestedReasoningEffort, traceparent, tracestate);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> trajectory(ScoreUser requester, String conversationId) {
        return conversations.trajectory(requester, conversationId);
    }

    @Transactional
    public boolean deleteConversation(ScoreUser requester, String conversationId) {
        return conversations.delete(requester, conversationId);
    }

    public void recordFailure(ChatRequest request, ScoreUser requester, String message) {
        recordFailure(request, requester, message, null, 0L);
    }

    public void recordFailure(ChatRequest request, ScoreUser requester, String message,
                              String failureClass) {
        recordFailure(request, requester, message, failureClass, 0L);
    }

    public void recordFailure(ChatRequest request, ScoreUser requester, String message,
                              String failureClass, long generation) {
        journal.recordFailure(request, requester, message, failureClass, generation);
    }
}

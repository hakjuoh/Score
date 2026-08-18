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
import org.oagi.score.gateway.http.api.ai_management.conversation.ConversationTitleGenerator;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.memory.AiContextBudgetService;
import org.oagi.score.gateway.http.api.ai_management.memory.ScoreChatMemoryFactory;
import org.oagi.score.gateway.http.api.ai_management.model.AiExecutionEvent;
import org.oagi.score.gateway.http.api.ai_management.policy.model.EffectiveAiPolicy;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiPolicyService;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiUsageAccountingService;
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
import java.util.concurrent.ConcurrentHashMap;

/** Transactional facade for assistant turns and conversation administration. */
@Service
public class ChatService {

    private final ScoreAiModelRegistry models;
    private final AgentIdentityProvider rootAgentIdentity;
    private final AiConversationUseCases conversations;
    private final ConversationSettingsManager settings;
    private final ChatTurnOrchestrator turns;
    private final ChatConversationJournal journal;
    private final AiPolicyService policyService;
    private final Map<String, AiExecutionEvent> policyNotices = new ConcurrentHashMap<>();
    private volatile AiUsageAccountingService accounting;

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
                       ConversationTitleGenerator titleGenerator,
                       ResponseOnlyAgent responseOnlyAgent, AiFileService files,
                       ScoreAiObservability observability,
                       ObjectProvider<ExecutionObserver> executionObservers,
                       AiPolicyService policyService) {
        this(models, new Dependencies(agentRunner, toolSearchAdvisor,
                chatMemoryFactory::create,
                requester -> repositoryFactory.aiChatConversationRepository(
                        requester, AiChatJsonSerializer.getInstance()),
                objectMapper, requests, contextBudgets, workflow, agentRunner,
                atifTrajectoryService, inputGuardrails, outputGuardrails, resultCommitter,
                compactor, titleGenerator, responseOnlyAgent, files, observability,
                executionObservers != null
                        ? executionObservers.getIfAvailable(ExecutionObserver::noop)
                        : ExecutionObserver.noop()), policyService);
    }

    ChatService(ScoreAiModelRegistry models, Dependencies dependencies) {
        this(models, dependencies, null);
    }

    ChatService(ScoreAiModelRegistry models, Dependencies dependencies,
                AiPolicyService policyService) {
        Dependencies value = Objects.requireNonNull(dependencies);
        this.models = Objects.requireNonNull(models);
        this.rootAgentIdentity = value.rootAgentIdentity();
        this.policyService = policyService;
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
        ChatTurnExecutor turnExecutor = new ChatTurnExecutor(value.workflow(),
                value.contextBudgets(), compactions, outputDiscloser);
        ChatResultCommitter results = new ChatResultCommitter(
                value.resultCommitter(), value.requests());
        ChatResponseFinalizer responses = new ChatResponseFinalizer(
                value.rootAgentIdentity(), outputDiscloser, results, value.files());
        ChatTurnCommitter turnCommitter = new ChatTurnCommitter(
                conversations, compactions, journal, results);
        ManualCompactionHandler manualCompactions = new ManualCompactionHandler(
                compactions, value.contextBudgets(), journal, results, responses);
        this.settings = new ConversationSettingsManager(models, prompts, conversations,
                value.contextBudgets(), compactions, journal, value.objectMapper(),
                observability, observer, value.requests(), policyService);
        this.turns = new ChatTurnOrchestrator(value.rootAgentIdentity(), prompts,
                conversations, value.objectMapper(), value.contextBudgets(),
                value.inputGuardrails(), observability, observer, compactions,
                journal, outputDiscloser, turnExecutor, turnCommitter, results, responses,
                manualCompactions, value.titleGenerator());
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
            ConversationTitleGenerator titleGenerator,
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

    public AiAssistantInfoRecord aiAssistantInfo(ScoreUser requester) {
        if (requester == null || policyService == null) return aiAssistantInfo();
        EffectiveAiPolicy policy = policyService.resolve(requester);
        if (!models.isAvailable()) return new AiAssistantInfoRecord(false, "AI_MODEL_NOT_CONFIGURED");
        if (!policy.aiEnabled()) return new AiAssistantInfoRecord(false, "AI_DISABLED_BY_POLICY");
        if (policy.availableModels().isEmpty()) return new AiAssistantInfoRecord(false, "AI_NO_ALLOWED_MODELS");
        if (accounting != null && accounting.isQuotaExhausted(policy)) {
            return new AiAssistantInfoRecord(false, "AI_QUOTA_EXHAUSTED");
        }
        return new AiAssistantInfoRecord(true, null);
    }

    @Autowired(required = false)
    void configureUsageAccounting(AiUsageAccountingService accounting) {
        this.accounting = accounting;
    }

    public EffectiveAiPolicy resolvePolicy(ScoreUser requester) {
        if (policyService == null) {
            throw new IllegalStateException("AI policy service is not configured.");
        }
        return policyService.resolve(requester);
    }

    public void snapshotPolicy(String requestId, EffectiveAiPolicy policy) {
        if (policyService != null) policyService.snapshot(requestId, policy);
        models.snapshot(requestId);
    }

    public void snapshotPolicyNotice(String requestId, AiExecutionEvent notice) {
        if (requestId != null && notice != null) policyNotices.put(requestId, notice);
    }

    public void clearPolicySnapshot(String requestId) {
        if (policyService != null) policyService.clearSnapshot(requestId);
        models.clearSnapshot(requestId);
        if (requestId != null) policyNotices.remove(requestId);
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

    @Transactional
    public ChatRequest prepare(ChatRequest request, ScoreUser requester,
                               long requestGeneration, boolean persistentWorkflowsAllowed) {
        return settings.prepare(request, requester, requestGeneration,
                persistentWorkflowsAllowed);
    }

    public ChatResponse chat(ChatRequest request, ScoreUser requester,
                             Consumer<AiExecutionEvent> progress) {
        return chat(request, requester, progress, 0L);
    }

    public ChatResponse chat(ChatRequest request, ScoreUser requester,
                             Consumer<AiExecutionEvent> progress,
                             long requestGeneration) {
        return turns.chat(request, requester, progress, requestGeneration,
                policyNotices.get(request.requestId()));
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

    public List<AiChatModelInfo> availableModels(ScoreUser requester) {
        if (policyService == null) return availableModels();
        EffectiveAiPolicy policy = policyService.resolve(requester);
        if (!policy.aiEnabled()) return List.of();
        java.util.Set<String> allowed = policy.availableModels().stream()
                .map(model -> model.descriptor().name())
                .collect(java.util.stream.Collectors.toSet());
        return conversations.availableModels().stream()
                .filter(model -> allowed.contains(model.name())).toList();
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
        EffectiveAiPolicy policy = null;
        if (policyService != null) {
            policy = policyService.resolve(requester);
            policy.requireModelAllowed(requestedModelName);
            policy.requireReasoningEffortAllowed(requestedModelName, requestedReasoningEffort);
        }
        return settings.update(requester, conversationId, requestedModelName,
                requestedReasoningEffort, traceparent, tracestate, policy);
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

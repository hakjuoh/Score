package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.cc_management.model.CcDocument;
import org.oagi.score.gateway.http.api.cc_management.model.CcDocumentImpl;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrails;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailHandlers;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinitionGeneratorAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.NameSuggesterAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.ChatExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.policy.service.AiPolicyService;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.api.ai_management.workflow.AgentRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasLength;

@Service
@Transactional(readOnly = true)
public class AiModelQueryService {

    private final RepositoryFactory repositoryFactory;
    private final AgentRunner runner;
    private final AiModelCatalog models;
    private final AgentOutputGuardrailChain outputGuardrails;
    private final DefinitionGeneratorAgent definitionGenerator;
    private final NameSuggesterAgent nameSuggester;
    private final ScoreAiObservability observability;
    private final PublicOutputDisclosureGate disclosureGate;
    private final AiPolicyService policyService;
    private volatile AiRequestRegistry requestRegistry;
    private volatile ScoreAiProperties aiProperties;
    private volatile ScoreAiModelRegistry runtimeModels;

    @Autowired
    public AiModelQueryService(RepositoryFactory repositoryFactory,
                               AgentRunner runner,
                               AiModelCatalog models,
                               AgentOutputGuardrailChain outputGuardrails,
                               DefinitionGeneratorAgent definitionGenerator,
                               NameSuggesterAgent nameSuggester,
                               ScoreAiObservability observability,
                               AiPolicyService policyService) {
        this.repositoryFactory = Objects.requireNonNull(repositoryFactory);
        this.runner = Objects.requireNonNull(runner);
        this.models = Objects.requireNonNull(models);
        this.outputGuardrails = Objects.requireNonNull(outputGuardrails);
        this.definitionGenerator = Objects.requireNonNull(definitionGenerator);
        this.nameSuggester = Objects.requireNonNull(nameSuggester);
        this.observability = Objects.requireNonNull(observability);
        this.policyService = policyService;
        this.disclosureGate = new PublicOutputDisclosureGate(
                this.outputGuardrails, this.observability);
    }

    @Autowired(required = false)
    void configureAdmission(AiRequestRegistry requestRegistry, ScoreAiProperties aiProperties,
                            ScoreAiModelRegistry runtimeModels) {
        this.requestRegistry = requestRegistry;
        this.aiProperties = aiProperties;
        this.runtimeModels = runtimeModels;
    }

    public AiModelQueryService(RepositoryFactory repositoryFactory,
                               AgentRunner runner,
                               AiModelCatalog models,
                               AgentOutputGuardrailChain outputGuardrails,
                               DefinitionGeneratorAgent definitionGenerator,
                               NameSuggesterAgent nameSuggester,
                               ScoreAiObservability observability) {
        this(repositoryFactory, runner, models, outputGuardrails, definitionGenerator,
                nameSuggester, observability, null);
    }

    AiModelQueryService(RepositoryFactory repositoryFactory,
                        AgentRunner runner,
                        AiModelCatalog models,
                        AgentOutputGuardrailChain outputGuardrails,
                        DefinitionGeneratorAgent definitionGenerator,
                        NameSuggesterAgent nameSuggester) {
        this(repositoryFactory, runner, models, outputGuardrails, definitionGenerator,
                nameSuggester, ScoreAiObservability.noop(), null);
    }

    public List<String> getAvailableModels() {
        return models.available().stream().map(model -> model.id().value()).toList();
    }

    public List<String> getAvailableModels(ScoreUser requester) {
        if (policyService == null) return getAvailableModels();
        var policy = policyService.resolve(requester);
        if (!policy.aiEnabled()) return List.of();
        return policy.availableModels().stream().map(model -> model.descriptor().name()).toList();
    }

    public String generateDefinition(
            ScoreUser requester, AccManifestId accManifestId, String model, String originalText) {
        return generateDefinition(requester, accManifestId, model, originalText, null, null);
    }

    public String generateDefinition(
            ScoreUser requester, AccManifestId accManifestId, String model, String originalText,
            String traceparent, String tracestate) {

        var accQuery = repositoryFactory.accQueryRepository(requester);
        var acc = accQuery.getAccSummary(accManifestId);

        CcDocument ccDocument = new CcDocumentImpl(requester, repositoryFactory, acc.release().releaseId());

        String userPrompt = "The object class term of the given object is '" + acc.objectClassTerm() + "'.\n";
        if (hasLength(originalText)) {
            userPrompt += "The original definition is '" + originalText + "'.\n";
        }
        userPrompt += prompt(accManifestId, ccDocument, 0);

        return run(definitionGenerator.definition(), requester, model, userPrompt,
                "definition_generation", traceparent, tracestate);
    }

    public String generateDefinition(
            ScoreUser requester, AsccpManifestId asccpManifestId, String model, String originalText) {
        return generateDefinition(requester, asccpManifestId, model, originalText, null, null);
    }

    public String generateDefinition(
            ScoreUser requester, AsccpManifestId asccpManifestId, String model, String originalText,
            String traceparent, String tracestate) {

        var asccpQuery = repositoryFactory.asccpQueryRepository(requester);
        var asccp = asccpQuery.getAsccpSummary(asccpManifestId);

        return generateDefinition(requester, asccp.roleOfAccManifestId(), model, originalText,
                traceparent, tracestate);
    }

    private String prompt(AccManifestId accManifestId, CcDocument ccDocument, int depth) {
        var acc = ccDocument.getAcc(accManifestId);
        StringBuilder sb = new StringBuilder();
        AccSummaryRecord basedAcc = null;
        if (acc.basedAccManifestId() != null) {
            basedAcc = ccDocument.getAcc(acc.basedAccManifestId());
            sb.append("'" + acc.objectClassTerm() + "' is derived from the base object '" + basedAcc.objectClassTerm() + "'.")
                    .append("\n");
            if (basedAcc.definition() != null && hasLength(basedAcc.definition().content())) {
                sb.append("The definition of the base object '" + basedAcc.objectClassTerm() + "' is '" + basedAcc.definition().content() + "'.").append("\n");
                if (hasLength(basedAcc.definition().source())) {
                    sb.append("The source of the base object's definition is '" + basedAcc.definition().source() + "'.").append("\n");
                }
            }
        }
        var asccList = ccDocument.getAsccListByFromAccManifestId(accManifestId).stream()
                .filter(e -> !e.den().contains("Extension. ")).collect(Collectors.toList());
        var bccList = ccDocument.getBccListByFromAccManifestId(accManifestId);
        if (asccList.size() + bccList.size() > 0) {
            sb.append("'" + acc.objectClassTerm() + "' has child elements, including ");
            List<String> childrenNames = new ArrayList<>();
            for (var ascc : asccList) {
                var asccp = ccDocument.getAsccp(ascc.toAsccpManifestId());
                childrenNames.add("'" + asccp.propertyTerm() + "' " +
                        "(Cardinality: " + ascc.cardinality().min() + ".." +
                        (ascc.cardinality().max() == -1 ? "*" : ascc.cardinality().max()) + ")");
            }
            for (var bcc : bccList) {
                var bccp = ccDocument.getBccp(bcc.toBccpManifestId());
                childrenNames.add("'" + bccp.propertyTerm() + "' " +
                        "(Cardinality: " + bcc.cardinality().min() + ".." +
                        (bcc.cardinality().max() == -1 ? "*" : bcc.cardinality().max()) + ")");
            }
            sb.append(childrenNames.stream().collect(Collectors.joining(", ")));
            sb.append(".").append("\n");
        }
        if (basedAcc != null) {
            sb.append(prompt(acc.basedAccManifestId(), ccDocument, depth));
        }
        return sb.toString();
    }

    private String removeReasoning(String content) {
        if (content != null && content.startsWith("<think>")) {
            int endIndex = content.indexOf("</think>");
            if (endIndex != -1) {
                return content.substring(endIndex + "</think>".length()).trim();
            }
        }
        return content;
    }

    public String suggestName(ScoreUser requester, AccManifestId accManifestId, String model, String originalName) {
        return suggestName(requester, accManifestId, model, originalName, null, null);
    }

    public String suggestName(ScoreUser requester, AccManifestId accManifestId, String model,
                              String originalName, String traceparent, String tracestate) {

        var accQuery = repositoryFactory.accQueryRepository(requester);
        var acc = accQuery.getAccSummary(accManifestId);

        CcDocument ccDocument = new CcDocumentImpl(requester, repositoryFactory, acc.release().releaseId());

        String userPrompt = "The original object class term of the given object is '" + originalName + "'.\n";
        if (acc.definition() != null && hasLength(acc.definition().content())) {
            userPrompt += "The definition is '" + acc.definition().content() + "'.\n";
        }
        userPrompt += prompt(accManifestId, ccDocument, 0);
        return run(nameSuggester.definition(), requester, model, userPrompt,
                "name_generation", traceparent, tracestate);
    }

    String run(AgentDefinition definition, ScoreUser requester,
               String modelId, String prompt, String executionKind,
               String traceparent, String tracestate) {
        var policy = policyService != null ? policyService.resolve(requester) : null;
        if (policy != null) policy.requireModelAllowed(modelId);
        String correlation = UUID.randomUUID().toString();
        String requesterId = requester != null && requester.userId() != null
                ? requester.userId().value().toString()
                : requester != null && hasLength(requester.username())
                ? requester.username() : "unknown";
        ExecutionScope scope = new ExecutionScope("ai-query-" + correlation,
                "ai-query-" + correlation, requesterId, 0L,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        AiRequestRegistry.Entry admission = null;
        if (requestRegistry != null) {
            int maximum = policy != null ? policy.maxActiveRequests() : 8;
            java.time.Duration timeout = aiProperties != null
                    ? aiProperties.getRequestInactivityTimeout() : java.time.Duration.ofMinutes(2);
            admission = requestRegistry.register(scope.requestId(), scope.conversationId(),
                    requester, Instant.now().plus(timeout), maximum);
            if (policyService != null) policyService.snapshot(scope.requestId(), policy);
            if (runtimeModels != null) runtimeModels.snapshot(scope.requestId());
            if (!requestRegistry.start(admission)) {
                requestRegistry.fail(admission, new CancellationException(
                        "Standalone AI execution stopped during admission."));
                if (policyService != null) policyService.clearSnapshot(scope.requestId());
                if (runtimeModels != null) runtimeModels.clearSnapshot(scope.requestId());
                throw new CancellationException(
                        "Standalone AI execution stopped during admission.");
            }
        }
        ScoreAiObservability.Turn turn = observability.startExecution(
                new ScoreAiObservability.ExecutionDescriptor(scope.requestId(),
                        scope.conversationId(), modelId, executionKind, "none"),
                requester, 0L, traceparent, tracestate);
        turn.executionStarted();
        try {
            AgentDefinition runDefinition = new AgentDefinition(definition.id(), definition.name(),
                    definition.description(), definition.instruction(),
                    (agent, context) -> new AgentRunRequest.Model(modelId,
                            definition.instruction().render(), new AiMessage.User(prompt),
                            List.of(), scope,
                            Map.of("feature", definition.id().value())),
                    org.oagi.score.gateway.http.api.ai_management.agent.AgentToolHandler.none(),
                    response -> new AgentDecision.Complete(new AgentOutput(
                            removeReasoning(response.result().response().content()),
                            response.result().metadata().attributes())),
                    guardrails(), false);
            ChatRequest transport = new ChatRequest(prompt, scope.requestId(),
                    definition.id().value(), scope.conversationId(), null, List.of(), null,
                    modelId, null, null);
            ChatExecutionContext execution = ChatExecutionContext.fromCoreMessages(
                    transport, List.of(), new AiMessage.User(prompt), requester, null,
                    false, false,
                    org.oagi.score.gateway.http.api.ai_management.agent.AgentToolPolicy.NONE, 0);
            AgentWorkflowContext workflow = AgentWorkflowContext.root(execution,
                    new AgentWorkflowContext.Request(scope.requestId(), scope.conversationId(),
                            requesterId, modelId, prompt, false, false, 1, "balanced",
                            null, false, false, false), 1);
            AgentDecision decision = runner.run(new DefinedAgent(runDefinition), workflow);
            if (!(decision instanceof AgentDecision.Complete complete)) {
                throw new IllegalStateException("Standalone Agent did not return a completed result.");
            }
            String generated = disclosureGate.require(complete.result(), scope,
                    Map.of("feature", definition.id().value()), definition.id());
            turn.complete("COMPLETED", null);
            return generated;
        } catch (RuntimeException failure) {
            if (admission != null) requestRegistry.fail(admission, failure);
            turn.complete(failure instanceof CancellationException ? "CANCELLED" : "FAILED", failure);
            throw failure;
        } finally {
            if (admission != null) requestRegistry.complete(admission);
            if (policyService != null) policyService.clearSnapshot(scope.requestId());
            if (runtimeModels != null) runtimeModels.clearSnapshot(scope.requestId());
        }
    }

    private AgentGuardrails guardrails() {
        return new AgentGuardrails(List.of(), List.of(AgentGuardrailHandlers.output(
                outputGuardrails, observability, AgentOutputGuardrail.Scope.PUBLIC,
                "agent_output", Map.of("feature", "standalone"))),
                AgentOutputGuardrail.Scope.PUBLIC);
    }
}

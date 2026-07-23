package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.cc_management.model.CcDocument;
import org.oagi.score.gateway.http.api.cc_management.model.CcDocumentImpl;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentFactory;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.ResolvedAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.DefinitionGeneratorAgent;
import org.oagi.score.gateway.http.api.ai_management.agent.NameSuggesterAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.execution.SpringAiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasLength;

@Service
@Transactional(readOnly = true)
public class AiModelQueryService {

    private final RepositoryFactory repositoryFactory;
    private final AgentExecutionService execution;
    private final SpringAiModelCatalog models;
    private final AgentOutputGuardrailChain outputGuardrails;
    private final AgentFactory agents = AgentFactory.binding();
    private final DefinitionGeneratorAgent definitionGenerator;
    private final NameSuggesterAgent nameSuggester;
    private final ScoreAiObservability observability;

    @Autowired
    public AiModelQueryService(RepositoryFactory repositoryFactory,
                               AgentExecutionService execution,
                               SpringAiModelCatalog models,
                               AgentOutputGuardrailChain outputGuardrails,
                               DefinitionGeneratorAgent definitionGenerator,
                               NameSuggesterAgent nameSuggester,
                               ScoreAiObservability observability) {
        this.repositoryFactory = Objects.requireNonNull(repositoryFactory);
        this.execution = Objects.requireNonNull(execution);
        this.models = Objects.requireNonNull(models);
        this.outputGuardrails = Objects.requireNonNull(outputGuardrails);
        this.definitionGenerator = Objects.requireNonNull(definitionGenerator);
        this.nameSuggester = Objects.requireNonNull(nameSuggester);
        this.observability = Objects.requireNonNull(observability);
    }

    AiModelQueryService(RepositoryFactory repositoryFactory,
                        AgentExecutionService execution,
                        SpringAiModelCatalog models,
                        AgentOutputGuardrailChain outputGuardrails,
                        DefinitionGeneratorAgent definitionGenerator,
                        NameSuggesterAgent nameSuggester) {
        this(repositoryFactory, execution, models, outputGuardrails, definitionGenerator,
                nameSuggester, ScoreAiObservability.noop());
    }

    public List<String> getAvailableModels() {
        return models.available().stream().map(model -> model.id().value()).toList();
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
        ResolvedAgent agent = agents.create(definition, models.require(modelId), ToolSet.empty());
        String correlation = UUID.randomUUID().toString();
        String requesterId = requester != null && requester.userId() != null
                ? requester.userId().value().toString()
                : requester != null && hasLength(requester.username())
                ? requester.username() : "unknown";
        ExecutionScope scope = new ExecutionScope("ai-query-" + correlation,
                "ai-query-" + correlation, requesterId, 0L,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        ScoreAiObservability.Turn turn = observability.startExecution(
                new ScoreAiObservability.ExecutionDescriptor(scope.requestId(),
                        scope.conversationId(), modelId, executionKind, "none"),
                requester, 0L, traceparent, tracestate);
        turn.executionStarted();
        try {
            var result = execution.execute(new AgentInvocation(null, agent,
                    new AiMessage.User(prompt), List.of(), scope, null));
            var guarded = outputGuardrails.evaluate(new AgentOutputGuardrail.Request(
                    AgentOutputGuardrail.Scope.PUBLIC, result.response(), scope,
                    Map.of("feature", definition.id().value())));
            observability.recordGuardrails(scope.requestId(), "agent_output",
                    guarded.decisions(), guarded.refusal());
            if (!guarded.allowed()) {
                throw new IllegalStateException(
                        "The generated content was not accepted by output policy.");
            }
            turn.complete("COMPLETED", null);
            return removeReasoning(guarded.output().content());
        } catch (RuntimeException failure) {
            turn.complete(failure instanceof CancellationException ? "CANCELLED" : "FAILED", failure);
            throw failure;
        }
    }
}

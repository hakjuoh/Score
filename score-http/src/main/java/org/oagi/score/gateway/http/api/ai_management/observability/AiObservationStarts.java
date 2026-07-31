package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Builds root workflow and provider model-call telemetry with canonical parentage. */
final class AiObservationStarts {

    private final Tracer tracer;
    private final AiObservationInstruments instruments;
    private final AiObservationEvents events;
    private final AiTurnRegistry registry;
    private final AiRequestModelResolver requestModels;

    AiObservationStarts(Tracer tracer, AiObservationInstruments instruments,
                        AiObservationEvents events, AiTurnRegistry registry,
                        AiRequestModelResolver requestModels) {
        this.tracer = tracer;
        this.instruments = instruments;
        this.events = events;
        this.registry = registry;
        this.requestModels = requestModels;
    }

    AiTurnTelemetry startExecution(ScoreAiObservability.ExecutionDescriptor execution,
                                   ScoreUser requester, long generation, Context parent) {
        String workflowName = AiObservationLabels.value(execution.kind());
        String requestModel = requestModels.resolve(execution.model());
        var startEvent = events.publish("workflow.root.started",
                events.scope(execution.requestId(), execution.conversationId(), requester,
                        generation, ExecutionScope.Purpose.USER_RESPONSE), Map.of(
                        "workflow", workflowName, "model_id", requestModel,
                        "permission_mode", AiObservationLabels.value(
                                execution.permissionMode())));
        SpanBuilder builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                        GenAiSemanticConventions.INVOKE_WORKFLOW, workflowName)).setParent(parent)
                .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.INVOKE_WORKFLOW)
                .setAttribute("gen_ai.workflow.name", workflowName)
                .setAttribute("score.ai.workflow.id", workflowName)
                .setAttribute("score.ai.workflow.run_id", execution.requestId())
                .setAttribute("gen_ai.request.model", requestModel)
                .setAttribute("score.ai.request.id", AiObservationLabels.value(execution.requestId()))
                .setAttribute("score.ai.conversation.id",
                        AiObservationLabels.value(execution.conversationId()))
                .setAttribute("score.ai.execution.kind", AiObservationLabels.value(execution.kind()))
                .setAttribute("score.ai.generation", generation)
                .setAttribute("score.ai.permission_mode",
                        AiObservationLabels.value(execution.permissionMode()));
        AiObservationEvents.startIdentity(builder, startEvent);
        putModelAlias(builder, execution.model(), requestModel);
        GenAiSemanticConventions.putIfKnown(builder, "gen_ai.request.reasoning.level",
                execution.reasoningLevel());
        if (StringUtils.hasText(execution.conversationId())) {
            builder.setAttribute("gen_ai.conversation.id", execution.conversationId().strip());
        }
        if (requester != null && requester.userId() != null) {
            builder.setAttribute("enduser.id", requester.userId().value().toString());
        }
        Span span = builder.startSpan();
        AtomicBoolean ended = new AtomicBoolean();
        AiTurnState state = new AiTurnState(
                execution.requestId(), execution.conversationId(), generation,
                requestModel, execution.reasoningLevel(), workflowName, span,
                ScoreAiObservability.privateContext(parent, span, ended),
                System.nanoTime(), ended, registry.turns);
        if (registry.turns.putIfAbsent(execution.requestId(), state) != null) {
            span.setAttribute("score.ai.duplicate_request_id", true);
            span.end();
            return null;
        }
        instruments.activeRequests.add(1, state.activeRequestAttributes);
        return new AiTurnTelemetry(state, instruments, registry.lifecycleEvents, events,
                registry.turns, registry.closeListeners, requestModels);
    }

    AiModelCallTelemetry startModelCall(
            String requestId, String modelAlias, String requestModel, String provider,
            String phase, String parentOperationId, String conversationId,
            AiTrajectoryRecorder.ExecutionEventIdentity started) {
        String semanticModel = StringUtils.hasText(requestModel)
                ? requestModel.strip() : AiObservationLabels.value(modelAlias);
        AiModelCallTelemetry inactive = noopModelCall(semanticModel, provider);
        return registry.withActiveTurn(requestId, inactive, turn -> {
            String semanticProvider = GenAiSemanticConventions.providerName(provider);
            Context explicit = registry.explicitParentContext(requestId, parentOperationId);
            Context parent = explicit != null ? explicit : registry.parentContext(requestId);
            var startEvent = started == null ? events.publish("model.call.started",
                    events.scope(requestId, conversationId, null, turn.generation,
                            ExecutionScope.Purpose.USER_RESPONSE), Map.of(
                            "model_id", semanticModel,
                            "provider", AiObservationLabels.value(semanticProvider),
                            "phase", AiObservationLabels.value(phase),
                            "parent_operation_id", AiObservationLabels.value(parentOperationId)))
                    : null;
            AiTurnState.ModelIdentity identity = turn.nextModelIdentity(parent);
            SpanBuilder builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                            GenAiSemanticConventions.CHAT, semanticModel))
                    .setParent(parent).setSpanKind(SpanKind.CLIENT)
                    .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.CHAT)
                    .setAttribute("gen_ai.provider.name", AiObservationLabels.value(semanticProvider))
                    .setAttribute("gen_ai.request.model", semanticModel)
                    .setAttribute("score.ai.model_call.id", identity.callId())
                    .setAttribute("score.ai.attempt", identity.attempt())
                    .setAttribute("score.ai.phase", AiObservationLabels.value(phase));
            AiObservationEvents.startIdentity(builder, startEvent);
            AiObservationEvents.startIdentity(builder, started);
            putModelAlias(builder, modelAlias, semanticModel);
            String semanticConversation = StringUtils.hasText(conversationId)
                    ? conversationId.strip() : turn.conversationId;
            if (StringUtils.hasText(semanticConversation)) {
                builder.setAttribute("gen_ai.conversation.id", semanticConversation);
            }
            GenAiSemanticConventions.putIfKnown(builder, "gen_ai.request.reasoning.level",
                    turn.reasoningLevel);
            if (turn.compacted.get()) builder.setAttribute("gen_ai.conversation.compacted", true);
            Span span = builder.startSpan();
            AiModelCallTelemetry telemetry = new AiModelCallTelemetry(
                    turn, span, semanticModel, semanticProvider, identity.agentInvocation(),
                    identity.sequence(), System.nanoTime(), true, instruments,
                    cost -> instruments.cost.record(cost.doubleValue(),
                            AiObservationLabels.providerModel(
                                    semanticProvider, semanticModel, null)));
            synchronized (turn) {
                turn.activeModelCalls.add(telemetry);
            }
            return telemetry;
        });
    }

    private AiModelCallTelemetry noopModelCall(String model, String provider) {
        return new AiModelCallTelemetry(null, Span.getInvalid(), model,
                GenAiSemanticConventions.providerName(provider), null, 0,
                System.nanoTime(), false, instruments, ignored -> { });
    }

    private static void putModelAlias(SpanBuilder builder, String alias, String requestModel) {
        if (StringUtils.hasText(alias) && !alias.strip().equals(requestModel)) {
            builder.setAttribute("score.ai.model.alias", alias.strip());
        }
    }
}

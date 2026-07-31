package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionEventPublisher;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.function.Supplier;

/** Records rejected request admission without creating active turn state. */
final class AiAdmissionObservation {

    private final Tracer tracer;
    private final AiObservationInstruments instruments;
    private final AiObservationEvents events;

    AiAdmissionObservation(Tracer tracer, AiObservationInstruments instruments,
                           AiObservationEvents events) {
        this.tracer = tracer;
        this.instruments = instruments;
        this.events = events;
    }

    void record(ChatRequest request, ScoreUser requester, Throwable failure, String reason,
                long generation, String requestModel, long startedNanos,
                Supplier<Context> parent) {
        String normalizedReason = AiObservationLabels.admissionReasonCategory(reason);
        var scope = events.scope(request.requestId(), request.conversationId(), requester,
                generation, ExecutionScope.Purpose.USER_RESPONSE);
        var rejection = events.publish("workflow.root.rejected", scope, Map.of(
                "outcome", "admission_rejected",
                "failure_type", failure != null
                        ? failure.getClass().getSimpleName() : "admission_rejected"));
        SpanBuilder builder = tracer.spanBuilder(GenAiSemanticConventions.spanName(
                        GenAiSemanticConventions.INVOKE_WORKFLOW, "assistant"))
                .setParent(parent.get())
                .setAttribute("gen_ai.operation.name", GenAiSemanticConventions.INVOKE_WORKFLOW)
                .setAttribute("gen_ai.workflow.name", "assistant")
                .setAttribute("gen_ai.request.model", requestModel)
                .setAttribute("score.ai.request.id", AiObservationLabels.value(request.requestId()))
                .setAttribute("score.ai.conversation.id",
                        AiObservationLabels.value(request.conversationId()))
                .setAttribute("score.ai.outcome", "admission_rejected")
                .setAttribute("score.ai.admission.reason", normalizedReason);
        AiObservationEvents.startIdentity(builder, rejection);
        if (StringUtils.hasText(request.modelName())
                && !request.modelName().strip().equals(requestModel)) {
            builder.setAttribute("score.ai.model.alias", request.modelName().strip());
        }
        if (StringUtils.hasText(request.conversationId())) {
            builder.setAttribute("gen_ai.conversation.id", request.conversationId().strip());
        }
        if (requester != null && requester.userId() != null) {
            builder.setAttribute("enduser.id", requester.userId().value().toString());
        }
        var span = builder.startSpan();
        if (failure != null) span.setAttribute("error.type", failure.getClass().getName());
        span.setStatus(StatusCode.ERROR, "admission_rejected");
        Attributes labels = Attributes.builder()
                .putAll(AiObservationLabels.model(requestModel, "admission_rejected"))
                .put("score.ai.admission.reason", normalizedReason).build();
        instruments.turns.add(1, labels);
        instruments.admissionRejections.add(1, labels);
        instruments.genAiWorkflowDuration.record(
                AiObservationTiming.elapsedSeconds(startedNanos),
                GenAiSemanticConventions.workflowDurationAttributes(
                        "assistant", failure != null ? failure.getClass().getName()
                                : "admission_rejected", false));
        var closed = events.publish(ExecutionEventPublisher.REQUEST_CLOSED, scope, Map.of(
                "outcome", "admission_rejected",
                "failure_type", failure != null
                        ? failure.getClass().getSimpleName() : "admission_rejected"));
        AiObservationEvents.terminalIdentity(span, closed);
        span.end();
    }
}

package org.oagi.score.gateway.http.api.ai_management.execution;

import io.modelcontextprotocol.spec.McpSchema;
import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangePermissionMode;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.service.AiElicitationService;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.configuration.ai.ConnectCenterMcpClientFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiChatOptionsFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiModelRegistry;
import org.oagi.score.gateway.http.configuration.ai.TrajectoryRecordingAdvisor;
import org.oagi.score.gateway.http.configuration.ai.ProviderCallAccountingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Coordinates one assistant conversation from model setup through bounded continuations. */
final class AiChatConversationRuntime {

    private final ScoreAiModelRegistry models;
    private final AiElicitationService elicitations;
    private final ScoreAiChatOptionsFactory optionsFactory;
    private final AiExecutionInstructions instructions;
    private final ScoreAiObservability observability;
    private final AiChatToolSessionFactory toolSessions;
    private final AiChatContinuationRunner continuations;
    private final AiChatModelInvoker modelInvoker;

    AiChatConversationRuntime(ScoreAiModelRegistry models,
                              AiElicitationService elicitations,
                              ScoreAiChatOptionsFactory optionsFactory,
                              AiExecutionInstructions instructions,
                              ScoreAiObservability observability,
                              AiChatToolSessionFactory toolSessions,
                              AiChatContinuationRunner continuations,
                              AiChatModelInvoker modelInvoker) {
        this.models = models;
        this.elicitations = elicitations;
        this.optionsFactory = optionsFactory;
        this.instructions = instructions;
        this.observability = observability;
        this.toolSessions = toolSessions;
        this.continuations = continuations;
        this.modelInvoker = modelInvoker;
    }

    AiChatExecutor.Result execute(AiChatExecutor.Context context,
                                  ConnectCenterMcpClientFactory.McpSession mcp,
                                  ExecutionState executionState, Agent.Instruction instruction,
                                  Runnable progress, WorkflowRunControl runControl) {
        var request = context.request();
        AiTrajectoryRecorder recorder = context.recorder();
        ChatOptions options = optionsFactory.create(
                request.modelName(), request.reasoningEffort(), request.routeManifest(),
                request.requestId());
        ScoreAiModelRegistry.ModelConfiguration model =
                models.modelConfiguration(request.modelName(), request.requestId());
        recorder.useModelProvider(model.providerType(), model.model());
        recordMcpTelemetry(recorder, mcp);
        long toolOutputTokenLimit = model.contextBudget() != null
                && model.contextBudget().toolOutputTokenLimit() != null
                ? model.contextBudget().toolOutputTokenLimit() : Long.MAX_VALUE;
        ExecutionScope scope = executionScope(context);
        ChatClient.Builder builder = models.clientBuilder(request.modelName(), request.requestId());
        if (modelInvoker.accounting() != null) {
            builder.defaultAdvisors(new ProviderCallAccountingAdvisor(
                    modelInvoker.accounting(), request, scope, null, model.providerType()));
        }
        builder.defaultAdvisors(new TrajectoryRecordingAdvisor(recorder, observability));
        AiChatToolSetup tools = toolSessions.prepare(context, mcp, executionState, progress,
                runControl, recorder, builder, toolOutputTokenLimit, scope);
        Agent.Instruction runtimeInstruction = tools.directToolCatalog().isEmpty()
                ? instruction
                : new Agent.Instruction(instruction.value() + tools.directToolCatalog());
        List<Message> messages = initialMessages(context);
        if (tools.guardedSession() != null && tools.executableTools() != null) {
            tools.guardedSession().executeApproved(tools.executableTools())
                    .ifPresent(execution -> AiApprovedChangeMessages.append(
                            messages, execution, recorder, toolOutputTokenLimit));
        }

        ChatClient assistant = builder.build();
        boolean internalPersona =
                context.executionPurpose() != ExecutionScope.Purpose.USER_RESPONSE;
        long completedToolCallsBeforeAnswer = recorder.completedToolCallCount();
        String answer = modelInvoker.invoke(assistant, options, request, messages, recorder,
                internalPersona, scope, executionState, runtimeInstruction, progress);
        AiChatContinuationRunner.Outcome outcome = continuations.run(
                answer, assistant, options, context, messages, recorder, tools,
                toolOutputTokenLimit, internalPersona, scope, executionState,
                runtimeInstruction, progress, runControl, completedToolCallsBeforeAnswer);
        return outcome.barrierCount() > 0 ? new AiChatExecutor.Result(outcome.answer(), Map.of(
                "approvalBarrierResolved", true,
                "approvalBarrierCount", outcome.barrierCount(),
                "approvedChangeCount", outcome.approved(),
                "deniedChangeCount", outcome.denied(),
                "failedChangeCount", outcome.failed()))
                : new AiChatExecutor.Result(outcome.answer());
    }

    private void recordMcpTelemetry(AiTrajectoryRecorder recorder,
                                    ConnectCenterMcpClientFactory.McpSession mcp) {
        if (mcp == null || mcp.tools() == null) {
            recorder.mcpToolNames(List.of());
            return;
        }
        recorder.mcpToolNames(java.util.Arrays.stream(mcp.tools().getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()).toList());
        var telemetry = mcp.telemetry();
        recorder.mcpTelemetry(telemetry.serverName(), telemetry.protocolVersion(),
                telemetry.serverAddress(), telemetry.serverPort(),
                telemetry.networkProtocolName(), telemetry.networkTransport());
    }

    private List<Message> initialMessages(AiChatExecutor.Context context) {
        List<Message> messages = new ArrayList<>(context.history());
        if (context.request().changeConfirmation() != null
                && context.request().changeConfirmation().revised()) {
            messages.add(new SystemMessage(instructions.render(
                    AiExecutionInstructions.Template.REVISED_CHANGE_CONTINUATION,
                    Map.of("toolName",
                            context.request().changeConfirmation().toolName())).value()));
        }
        messages.add(context.userMessage());
        return messages;
    }

    McpSchema.ElicitResult handleElicitation(AiChatExecutor.Context context,
                                             McpSchema.ElicitFormRequest elicitation,
                                             WorkflowRunControl runControl) {
        if (AiChangePermissionMode.resolve(context.request().permissionMode())
                == AiChangePermissionMode.FULL_ACCESS
                && isConfirmationOnly(elicitation.requestedSchema())) {
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of());
        }
        runControl.definiteActivityStarted();
        try {
            return elicitations.await(context.requester(), context.request().conversationId(),
                    context.request().requestId(), context.recorder().executionGeneration(),
                    elicitation, context.recorder()::elicitationRequired);
        } finally {
            runControl.definiteActivityFinished();
        }
    }

    boolean supportsElicitation() {
        return elicitations != null;
    }

    static ExecutionScope executionScope(AiChatExecutor.Context context) {
        String requesterId = context.requester() != null && context.requester().userId() != null
                ? context.requester().userId().value().toString()
                : context.requester() != null
                && StringUtils.hasText(context.requester().username())
                ? context.requester().username() : "unknown";
        return new ExecutionScope(context.request().requestId(),
                context.request().conversationId(), requesterId,
                context.recorder().executionGeneration(), context.executionPurpose(),
                context.guardrailDecisionIds());
    }

    private boolean isConfirmationOnly(Map<String, Object> schema) {
        if (schema == null
                || !(schema.get("properties") instanceof Map<?, ?> properties)
                || !properties.isEmpty()) return false;
        return !(schema.get("required") instanceof java.util.Collection<?> required)
                || required.isEmpty();
    }
}

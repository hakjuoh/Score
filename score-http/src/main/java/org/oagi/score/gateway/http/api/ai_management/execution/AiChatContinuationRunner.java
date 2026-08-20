package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalScope;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiResolvedChange;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeToolGuard;
import org.oagi.score.gateway.http.api.ai_management.trajectory.AiTrajectoryRecorder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Drives textual-call recovery, approval waves, and progress-aware post-change read-back. */
final class AiChatContinuationRunner {

    // Productive continuations may legitimately complete any number of requested changes.
    // Stop only after consecutive continuations make no objective change progress.
    private static final int MAX_STALLED_READ_BACK_CONTINUATIONS = 2;
    private static final int MAX_TEXTUAL_TOOL_CALL_RECOVERIES = 2;
    private static final int MAX_INCOMPLETE_TOOL_NARRATION_RECOVERIES = 2;
    private static final Pattern TEXTUAL_TOOL_CALL_PLACEHOLDER = Pattern.compile(
            "(?is)\\*{0,2}\\[\\s*tool(?:[ -]call)?\\s*:\\s*[^\\]\\r\\n]+]\\*{0,2}"
                    + "(?:\\s*(?:→|->).*?)?\\s*$");
    private static final Pattern INCOMPLETE_TOOL_NARRATION = Pattern.compile(
            "(?is)^\\s*(?:[-*]\\s*)?(?:I(?:['’]ll|\\s+will|\\s+am\\s+going\\s+to)"
                    + "|Let\\s+me)\\s+(?:first\\s+)?(?:locate|find|search|look\\s+up|"
                    + "inspect|check|retrieve|review|verify|create|update|delete|add|enable|"
                    + "disable|profile|compare|gather|load|open)\\b.*$");

    private final AiChangeApprovalCoordinator approvals;
    private final AiExecutionInstructions instructions;
    private final AiChatModelInvoker modelInvoker;

    AiChatContinuationRunner(AiChangeApprovalCoordinator approvals,
                             AiExecutionInstructions instructions,
                             AiChatModelInvoker modelInvoker) {
        this.approvals = approvals;
        this.instructions = instructions;
        this.modelInvoker = modelInvoker;
    }

    Outcome run(String answer, ChatClient assistant, ChatOptions options,
                AiChatExecutor.Context context, List<Message> initial,
                AiTrajectoryRecorder recorder, AiChatToolSetup tools, long tokenLimit,
                boolean internalPersona, ExecutionScope scope, ExecutionState state,
                Agent.Instruction instruction, Runnable progress,
                WorkflowRunControl runControl, long completedToolCallsBeforeAnswer,
                long successfulDomainToolCallsBeforeAnswer) {
        answer = recoverTextualToolCalls(answer, assistant, options, context, initial, recorder,
                internalPersona, scope, state, instruction, progress,
                completedToolCallsBeforeAnswer);
        answer = recoverIncompleteToolNarration(answer, assistant, options, context, initial,
                recorder, internalPersona, scope, state, instruction, progress,
                successfulDomainToolCallsBeforeAnswer);
        Outcome approval = resolveApprovals(answer, assistant, options, context, initial,
                recorder, tools, tokenLimit, internalPersona, scope, state,
                instruction, progress, runControl);
        return approval.withAnswer(completeReadBack(approval.answer(), assistant, options,
                context, recorder, tools.guardedSession(), tokenLimit, internalPersona,
                scope, state, instruction, progress));
    }

    private String recoverTextualToolCalls(String answer, ChatClient assistant,
                                           ChatOptions options, AiChatExecutor.Context context,
                                           List<Message> messages, AiTrajectoryRecorder recorder,
                                           boolean internalPersona, ExecutionScope scope,
                                           ExecutionState state, Agent.Instruction instruction,
                                           Runnable progress, long completedBefore) {
        int recovery = 0;
        while (isTextualToolCallPlaceholder(answer)
                && recorder.completedToolCallCount() == completedBefore
                && recovery++ < MAX_TEXTUAL_TOOL_CALL_RECOVERIES) {
            List<Message> retry = new ArrayList<>(messages);
            retry.add(new AssistantMessage(answer));
            retry.add(new UserMessage(instructions.render(
                    AiExecutionInstructions.Template.TEXTUAL_TOOL_CALL_RECOVERY).value()));
            answer = modelInvoker.invoke(assistant, options, context.request(), retry, recorder,
                    internalPersona, scope, state, instruction, progress);
        }
        if (isTextualToolCallPlaceholder(answer)) {
            throw new IllegalStateException(
                    "The assistant repeatedly returned a textual tool-call placeholder.");
        }
        return answer;
    }

    private String recoverIncompleteToolNarration(
            String answer, ChatClient assistant, ChatOptions options,
            AiChatExecutor.Context context, List<Message> messages,
            AiTrajectoryRecorder recorder, boolean internalPersona, ExecutionScope scope,
            ExecutionState state, Agent.Instruction instruction, Runnable progress,
            long successfulDomainBefore) {
        if (!isIncompleteToolNarration(answer, context)) {
            return answer;
        }
        int recovery = 0;
        while (recovery++ < MAX_INCOMPLETE_TOOL_NARRATION_RECOVERIES) {
            List<Message> retry = new ArrayList<>(messages);
            retry.add(new AssistantMessage(answer));
            retry.add(new UserMessage(instructions.render(
                    AiExecutionInstructions.Template.INCOMPLETE_TOOL_NARRATION_RECOVERY).value()));
            answer = modelInvoker.invoke(assistant, options, context.request(), retry, recorder,
                    internalPersona, scope, state, instruction, progress);
            if (recorder.successfulDomainToolCallCount() > successfulDomainBefore
                    && !isIncompleteToolNarration(answer, context)) {
                return answer;
            }
        }
        throw new IllegalStateException(
                "The assistant repeatedly announced Tool work without completing domain work.");
    }

    private Outcome resolveApprovals(String answer, ChatClient assistant, ChatOptions options,
                                     AiChatExecutor.Context context, List<Message> initial,
                                     AiTrajectoryRecorder recorder, AiChatToolSetup tools,
                                     long tokenLimit, boolean internalPersona,
                                     ExecutionScope scope, ExecutionState state,
                                     Agent.Instruction instruction, Runnable progress,
                                     WorkflowRunControl runControl) {
        List<Message> messages = new ArrayList<>(initial);
        int barriers = 0, approved = 0, denied = 0, failed = 0;
        while (tools.guardedSession() != null && approvals != null
                && !tools.guardedSession().pendingApprovals().isEmpty()) {
            List<AiPendingChangeApproval> pending =
                    List.copyOf(tools.guardedSession().pendingApprovals());
            AiChangeApprovalScope approvalScope = context.approvalScope() != null
                    ? context.approvalScope()
                    : AiChangeApprovalScope.root(context.request().conversationId());
            List<AiResolvedChange> resolutions;
            try {
                context.approvalWaitLifecycle().suspendForApproval();
                Map<String, AiChangeApprovalResolution> decisions;
                try {
                    runControl.definiteActivityStarted();
                    try {
                        decisions = approvals.awaitDecisions(context.requester(),
                                context.request().requestId(), context.request().conversationId(),
                                approvalScope, pending, recorder::changeApprovalBatchRequired,
                                recorder::changeApprovalDecisionAccepted,
                                recorder.executionGeneration());
                    } finally {
                        runControl.definiteActivityFinished();
                    }
                } finally {
                    context.approvalWaitLifecycle().resumeAfterApproval();
                }
                resolutions = tools.guardedSession().resolveApprovals(
                        tools.executableTools(), decisions);
            } finally {
                recorder.changeApprovalsResolved(pending);
            }
            barriers++;
            int executed = (int) resolutions.stream().filter(AiResolvedChange::executed).count();
            // An approved call that failed during execution is not a denial. The user approved
            // it, so it belongs in the failed bucket and remains distinguishable in telemetry.
            int rejected = (int) resolutions.stream().filter(resolution -> !resolution.executed())
                    .filter(resolution -> resolution.result() != null
                            && resolution.result().contains(
                            AiChangeToolGuard.CHANGE_CONFIRMATION_DENIED)).count();
            approved += executed;
            denied += rejected;
            failed += resolutions.size() - executed - rejected;
            messages.add(new AssistantMessage(answer));
            resolutions.forEach(resolution -> AiApprovedChangeMessages.append(
                    messages, resolution, recorder, tokenLimit));
            messages.add(new UserMessage(instructions.render(
                    AiExecutionInstructions.Template.APPROVAL_CONTINUATION).value()));
            answer = modelInvoker.invoke(assistant, options, context.request(), messages, recorder,
                    internalPersona, scope, state, instruction, progress);
        }
        return new Outcome(answer, barriers, approved, denied, failed);
    }

    private String completeReadBack(String answer, ChatClient assistant, ChatOptions options,
                                    AiChatExecutor.Context context, AiTrajectoryRecorder recorder,
                                    AiChangeToolGuard.GuardedToolSession guarded, long tokenLimit,
                                    boolean internalPersona, ExecutionScope scope,
                                    ExecutionState state, Agent.Instruction instruction,
                                    Runnable progress) {
        int stalledContinuations = 0;
        while (readBackRequired(guarded)
                && stalledContinuations < MAX_STALLED_READ_BACK_CONTINUATIONS) {
            int completedChangesBefore = guarded.completedChanges().size();
            List<Message> messages = new ArrayList<>(context.history());
            messages.add(context.userMessage());
            guarded.completedChanges().forEach(execution -> AiApprovedChangeMessages.append(
                    messages, execution, recorder, tokenLimit));
            messages.add(new AssistantMessage(answer));
            messages.add(new UserMessage(instructions.render(
                    AiExecutionInstructions.Template.READ_BACK_CONTINUATION).value()));
            answer = modelInvoker.invoke(assistant, options, context.request(), messages, recorder,
                    internalPersona, scope, state, instruction, progress);
            stalledContinuations = guarded.completedChanges().size() > completedChangesBefore
                    ? 0 : stalledContinuations + 1;
        }
        if (readBackRequired(guarded)) {
            throw new AiChangeReadBackException(guarded.completedChanges().size());
        }
        return answer;
    }

    private boolean readBackRequired(AiChangeToolGuard.GuardedToolSession guarded) {
        return guarded != null && guarded.changeCompleted() && !guarded.confirmationRequired()
                && !guarded.readAfterLastChange();
    }

    private boolean isTextualToolCallPlaceholder(String answer) {
        return StringUtils.hasText(answer) && TEXTUAL_TOOL_CALL_PLACEHOLDER.matcher(answer).find();
    }

    private boolean isIncompleteToolNarration(String answer, AiChatExecutor.Context context) {
        return context.toolsEnabled() && StringUtils.hasText(answer)
                && INCOMPLETE_TOOL_NARRATION.matcher(answer).matches();
    }

    record Outcome(String answer, int barrierCount, int approved, int denied, int failed) {
        Outcome withAnswer(String value) {
            return new Outcome(value, barrierCount, approved, denied, failed);
        }
    }
}

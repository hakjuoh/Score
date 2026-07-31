package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.model.AiChangeAuthorization;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationNotice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiApprovedExecution;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingChangeApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiResolvedChange;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChangeConfirmation;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.api.ai_management.execution.AiGuardedToolCallback;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolAuthorizationPolicy;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Enforces explicit, one-time approval before any connectCenter MCP tool call the server did not declare read-only. */
@Component
public class AiChangeToolGuard {

    public static final String CHANGE_CONFIRMATION_REQUIRED = "CHANGE_CONFIRMATION_REQUIRED";
    public static final String CHANGE_CONFIRMATION_DENIED = "CHANGE_CONFIRMATION_DENIED";
    public static final String CHANGE_FAILED = "CHANGE_FAILED";
    public static final String REQUEST_STOPPING = "REQUEST_STOPPING";

    /*
     * Read-only classification comes from the MCP server itself: the session's
     * read-only set contains exactly the tool names the server declared with the
     * readOnlyHint tool annotation, resolved at session open. The set is exact and
     * case-sensitive, so a data-changing tool cannot bypass approval merely by
     * using a get_* prefix, and every unannotated/unknown/future name requires
     * approval by default.
     */
    private static final String REQUEST_STOPPING_RESULT =
            "{\"error\":\"" + REQUEST_STOPPING
                    + "\",\"message\":\"The request is stopping;"
                    + " the data-changing tool was not executed.\"}";
    private static final String CONFIRMATION_DENIED_RESULT =
            "{\"error\":\"" + CHANGE_CONFIRMATION_DENIED
                    + "\",\"message\":\"The user denied this data-changing tool call.\"}";

    private final AiChangeConfirmationService confirmations;
    private final AiRequestRegistry requests;
    private final AiChangeApprovalPolicy approvalPolicy;
    private final AiToolInputNormalizer inputNormalizer;
    private final AiChangeToolResults results;

    public AiChangeToolGuard(AiChangeConfirmationService confirmations, AiRequestRegistry requests) {
        this(confirmations, requests, AiChangeOwnershipPolicy.UNVERIFIED, new ObjectMapper());
    }

    public AiChangeToolGuard(AiChangeConfirmationService confirmations, AiRequestRegistry requests,
                               AiChangeOwnershipPolicy ownership) {
        this(confirmations, requests, ownership, new ObjectMapper());
    }

    @Autowired
    public AiChangeToolGuard(AiChangeConfirmationService confirmations, AiRequestRegistry requests,
                               AiChangeOwnershipPolicy ownership, ObjectMapper objectMapper) {
        this.confirmations = confirmations;
        this.requests = requests;
        this.approvalPolicy = new AiChangeApprovalPolicy(ownership);
        this.results = new AiChangeToolResults(objectMapper);
        this.inputNormalizer = new AiToolInputNormalizer(objectMapper);
    }

    public ToolCallbackProvider guard(ChatRequest request, ScoreUser requester,
                                      Consumer<AiChangeConfirmationNotice> noticeConsumer,
                                      ToolCallbackProvider delegate, Set<String> readOnlyToolNames) {
        return session(request, requester, noticeConsumer, delegate, readOnlyToolNames);
    }

    public GuardedToolSession session(ChatRequest request, ScoreUser requester,
                                      Consumer<AiChangeConfirmationNotice> noticeConsumer,
                                      ToolCallbackProvider delegate, Set<String> readOnlyToolNames) {
        return session(request, requester, noticeConsumer, delegate, readOnlyToolNames,
                WorkflowRunControl.NOOP);
    }

    public GuardedToolSession session(ChatRequest request, ScoreUser requester,
                                      Consumer<AiChangeConfirmationNotice> noticeConsumer,
                                      ToolCallbackProvider delegate, Set<String> readOnlyToolNames,
                                      WorkflowRunControl runControl) {
        ToolCallback[] callbacks = delegate != null ? delegate.getToolCallbacks() : new ToolCallback[0];
        ToolCallback[] guarded = new ToolCallback[callbacks.length];
        Set<String> emitted = ConcurrentHashMap.newKeySet();
        Consumer<AiChangeConfirmationNotice> notices = notice -> {
            if (emitted.add(notice.confirmationRequestId())) {
                noticeConsumer.accept(notice);
            }
        };
        GuardedToolSession session = new GuardedToolSession(
                request, requester, notices, guarded, readOnlyToolNames, runControl);
        for (int index = 0; index < callbacks.length; index++) {
            guarded[index] = new AiGuardedToolCallback(
                    callbacks[index], session, inputNormalizer);
        }
        return session;
    }

    /**
     * Creates the protocol-neutral authorization middleware used by the common Tool gateway.
     * The provider callbacks remain unwrapped; authorization and execution fencing are applied
     * by {@link org.oagi.score.gateway.http.api.ai_management.tool.ToolExecutionGateway}.
     */
    public GuardedToolSession authorizationSession(
            ChatRequest request, ScoreUser requester,
            Consumer<AiChangeConfirmationNotice> noticeConsumer,
            ToolCallbackProvider delegate, Set<String> readOnlyToolNames) {
        return authorizationSession(request, requester, noticeConsumer, delegate,
                readOnlyToolNames, WorkflowRunControl.NOOP);
    }

    public GuardedToolSession authorizationSession(
            ChatRequest request, ScoreUser requester,
            Consumer<AiChangeConfirmationNotice> noticeConsumer,
            ToolCallbackProvider delegate, Set<String> readOnlyToolNames,
            WorkflowRunControl runControl) {
        ToolCallback[] callbacks = delegate != null ? delegate.getToolCallbacks() : new ToolCallback[0];
        Set<String> emitted = ConcurrentHashMap.newKeySet();
        Consumer<AiChangeConfirmationNotice> notices = notice -> {
            if (emitted.add(notice.confirmationRequestId())) {
                noticeConsumer.accept(notice);
            }
        };
        return new GuardedToolSession(request, requester, notices,
                callbacks, readOnlyToolNames, runControl);
    }

    /** Returns only the tools the server declared read-only, fail-closed, for specialist agents. */
    public ToolCallbackProvider readOnly(ToolCallbackProvider delegate, Set<String> readOnlyToolNames) {
        ToolCallback[] callbacks = delegate != null ? delegate.getToolCallbacks() : new ToolCallback[0];
        ToolCallback[] readOnly = Arrays.stream(callbacks)
                .filter(callback -> !isChange(callback.getToolDefinition().name(), readOnlyToolNames))
                .toArray(ToolCallback[]::new);
        return () -> readOnly;
    }

    static boolean isChange(String toolName, Set<String> readOnlyToolNames) {
        return toolName == null || readOnlyToolNames == null
                || !readOnlyToolNames.contains(toolName);
    }

    /** Request-scoped tool state used to resume an exact approval and enforce read-back. */
    public final class GuardedToolSession implements ToolCallbackProvider, ToolAuthorizationPolicy {
        private final ChatRequest request;
        private final ScoreUser requester;
        private final Consumer<AiChangeConfirmationNotice> notices;
        private final ToolCallback[] callbacks;
        private final Set<String> readOnlyToolNames;
        private final AiChangeExecutionLease changeLease;
        private final AtomicLong sequence = new AtomicLong();
        private final CopyOnWriteArrayList<AiApprovedExecution> completedChanges =
                new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<AiPendingChangeApproval> pendingApprovals =
                new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, ChangeConfirmation> redemptions =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> resolvedChangeResults =
                new ConcurrentHashMap<>();
        private volatile long lastChangeSequence = -1L;
        private volatile long lastReadSequence = -1L;
        private volatile boolean confirmationRequired;

        private GuardedToolSession(ChatRequest request, ScoreUser requester,
                                   Consumer<AiChangeConfirmationNotice> notices,
                                   ToolCallback[] callbacks,
                                   Set<String> readOnlyToolNames,
                                   WorkflowRunControl runControl) {
            this.request = request;
            this.requester = requester;
            this.notices = notices;
            this.callbacks = callbacks;
            this.readOnlyToolNames = readOnlyToolNames != null ? Set.copyOf(readOnlyToolNames) : Set.of();
            WorkflowRunControl requiredRunControl =
                    Objects.requireNonNullElse(runControl, WorkflowRunControl.NOOP);
            this.changeLease = new AiChangeExecutionLease(
                    request.requestId(), requests, requiredRunControl);
        }

        public boolean isChange(String toolName) {
            return AiChangeToolGuard.isChange(toolName, readOnlyToolNames);
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            return callbacks;
        }

        @Override
        public ToolAuthorizationPolicy.Result authorize(ToolAuthorizationPolicy.Request authorization) {
            String name = authorization.tool().name();
            String normalizedInput = inputNormalizer.normalize(
                    authorization.arguments().json(), authorization.tool().inputSchema());
            if (!isChange(name)) {
                return new ToolAuthorizationPolicy.Result.Allow("read-only");
            }
            Optional<String> cached = cachedResult(name, normalizedInput);
            if (cached.isPresent()) {
                return new ToolAuthorizationPolicy.Result.Refuse(
                        new AiTool.ToolResult(cached.get()));
            }
            ChangeConfirmation supplied = redemption(name, normalizedInput)
                    .orElse(request.changeConfirmation());
            if (supplied != null
                    || approvalPolicy.requiresApproval(
                    request.permissionMode(), requester, name, normalizedInput)) {
                AiChangeAuthorization result = confirmations.authorize(
                        requester, request.conversationId(), request.requestId(), supplied,
                        name, normalizedInput);
                if (!result.allowed()) {
                    markConfirmationRequired(result.notice(), name, normalizedInput);
                    notices.accept(result.notice());
                    return new ToolAuthorizationPolicy.Result.Refuse(new AiTool.ToolResult(
                            results.confirmationRequired(result.notice().confirmationRequestId())));
                }
            }
            return new ToolAuthorizationPolicy.Result.Allow(
                    supplied != null ? supplied.confirmationRequestId() : "permission-mode");
        }

        /** Applies the legacy Spring AI callback approval protocol for one normalized call. */
        public Optional<String> approvalRefusal(String name, String normalizedInput) {
            ChangeConfirmation supplied = redemption(name, normalizedInput)
                    .orElse(request.changeConfirmation());
            if (supplied == null && !approvalPolicy.requiresApproval(
                    request.permissionMode(), requester, name, normalizedInput)) {
                return Optional.empty();
            }
            AiChangeAuthorization authorization = confirmations.authorize(
                    requester, request.conversationId(), request.requestId(), supplied,
                    name, normalizedInput);
            if (authorization.allowed()) {
                return Optional.empty();
            }
            markConfirmationRequired(authorization.notice(), name, normalizedInput);
            notices.accept(authorization.notice());
            return Optional.of(results.confirmationRequired(
                    authorization.notice().confirmationRequestId()));
        }

        @Override
        public ToolAuthorizationPolicy.Result beforeExecution(
                ToolAuthorizationPolicy.Request authorization) {
            if (!isChange(authorization.tool().name())) {
                return new ToolAuthorizationPolicy.Result.Allow("read-only");
            }
            if (!startChange(executionKey(authorization))) {
                return new ToolAuthorizationPolicy.Result.Refuse(
                        new AiTool.ToolResult(REQUEST_STOPPING_RESULT));
            }
            return new ToolAuthorizationPolicy.Result.Allow("change-fenced");
        }

        @Override
        public void afterExecution(ToolAuthorizationPolicy.Request authorization,
                                   AiTool.ToolResult result) {
            if (isChange(authorization.tool().name())) {
                try {
                    changeCompleted(authorization.tool().name(),
                            authorization.arguments().json(), result.json());
                } finally {
                    releaseChangeLease(authorization);
                }
            } else {
                readCompleted();
            }
        }

        @Override
        public void afterFailure(ToolAuthorizationPolicy.Request authorization,
                                 RuntimeException failure) {
            if (isChange(authorization.tool().name())) {
                releaseChangeLease(authorization);
            }
        }

        @Override
        public void afterAborted(ToolAuthorizationPolicy.Request authorization) {
            if (isChange(authorization.tool().name())) {
                releaseChangeLease(authorization);
            }
        }

        public Optional<AiApprovedExecution> executeApproved(ToolCallbackProvider executableTools) {
            var supplied = request.changeConfirmation();
            if (supplied == null || supplied.revised()
                    || !StringUtils.hasText(supplied.toolName())
                    || !StringUtils.hasText(supplied.arguments())) {
                return Optional.empty();
            }
            if (!isChange(supplied.toolName())) {
                throw new IllegalArgumentException("An approved change must target a data-changing tool.");
            }
            ToolCallback callback = Arrays.stream(executableTools.getToolCallbacks())
                    .filter(candidate -> supplied.toolName().equals(candidate.getToolDefinition().name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The approved change tool is not available: " + supplied.toolName()));
            long changesBefore = lastChangeSequence;
            String result;
            try {
                result = callback.call(supplied.arguments(), new ToolContext(Map.of()));
            } catch (RuntimeException failure) {
                return Optional.of(new AiApprovedExecution(supplied.toolName(),
                        supplied.arguments(), results.changeFailed(failure)));
            }
            if (lastChangeSequence == changesBefore) {
                return Optional.empty();
            }
            AiApprovedExecution execution = new AiApprovedExecution(
                    supplied.toolName(), supplied.arguments(), result);
            return Optional.of(execution);
        }

        public List<AiPendingChangeApproval> pendingApprovals() {
            return List.copyOf(pendingApprovals);
        }

        /** Executes approved invocations through the normal recording callbacks and resolves denials. */
        public List<AiResolvedChange> resolveApprovals(
                ToolCallbackProvider executableTools,
                Map<String, AiChangeApprovalResolution> resolutions) {
            if (pendingApprovals.isEmpty()) {
                return List.of();
            }
            List<AiPendingChangeApproval> wave = List.copyOf(pendingApprovals);
            pendingApprovals.clear();
            List<AiResolvedChange> resolved = new java.util.ArrayList<>(wave.size());
            for (AiPendingChangeApproval approval : wave) {
                AiChangeApprovalResolution resolution = resolutions.get(
                        approval.notice().confirmationRequestId());
                if (resolution == null || !resolution.approved()) {
                    resolvedChangeResults.put(
                            key(approval.toolName(), approval.arguments()),
                            CONFIRMATION_DENIED_RESULT);
                    resolved.add(new AiResolvedChange(approval.toolName(), approval.arguments(),
                            CONFIRMATION_DENIED_RESULT, false));
                    continue;
                }
                ChangeConfirmation authorization = new ChangeConfirmation(
                        approval.notice().confirmationRequestId(), resolution.confirmationGrant(),
                        approval.toolName(), approval.arguments());
                redemptions.put(key(approval.toolName(), approval.arguments()), authorization);
                ToolCallback callback = Arrays.stream(executableTools.getToolCallbacks())
                        .filter(candidate -> approval.toolName().equals(
                                candidate.getToolDefinition().name()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "The approved change tool is not available: " + approval.toolName()));
                long changesBefore = lastChangeSequence;
                String result;
                try {
                    result = callback.call(approval.arguments(), new ToolContext(Map.of()));
                } catch (RuntimeException failure) {
                    /*
                     * An approved tool that fails is an outcome the assistant has to
                     * account for, not a reason to end the turn: a tool that runs
                     * without approval hands its failure back as the tool result, so
                     * an approved one does the same and the user is told what stopped
                     * the change. The result is deliberately not cached, so the
                     * assistant can retry once the obstacle is gone -- under a fresh
                     * approval, because the redemption is already spent.
                     */
                    resolved.add(new AiResolvedChange(approval.toolName(),
                            approval.arguments(), results.changeFailed(failure), false));
                    continue;
                }
                if (lastChangeSequence == changesBefore) {
                    throw new IllegalStateException(
                            "An approved change could not be executed: " + approval.toolName());
                }
                resolved.add(new AiResolvedChange(
                        approval.toolName(), approval.arguments(), result, true));
            }
            confirmationRequired = !pendingApprovals.isEmpty();
            return List.copyOf(resolved);
        }

        public boolean changeCompleted() {
            return lastChangeSequence >= 0L;
        }

        public boolean readAfterLastChange() {
            return lastChangeSequence >= 0L && lastReadSequence > lastChangeSequence;
        }

        public boolean confirmationRequired() {
            return confirmationRequired;
        }

        public List<AiApprovedExecution> completedChanges() {
            return List.copyOf(completedChanges);
        }

        void markConfirmationRequired(
                AiChangeConfirmationNotice notice, String toolName, String arguments) {
            confirmationRequired = true;
            boolean recorded = pendingApprovals.stream().anyMatch(existing ->
                    existing.notice().confirmationRequestId().equals(notice.confirmationRequestId()));
            if (!recorded) {
                pendingApprovals.add(new AiPendingChangeApproval(notice, toolName, arguments));
            }
        }

        Optional<ChangeConfirmation> redemption(String toolName, String arguments) {
            if (redemptions.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(redemptions.remove(key(toolName, arguments)));
        }

        private String key(String toolName, String arguments) {
            String digest = confirmations.argumentsDigest(toolName, arguments);
            return StringUtils.hasText(digest)
                    ? digest : Objects.toString(toolName, "") + "\u0000"
                    + Objects.toString(arguments, "");
        }

        public void readCompleted() {
            lastReadSequence = sequence.incrementAndGet();
        }

        public void changeCompleted(String toolName, String input, String result) {
            lastChangeSequence = sequence.incrementAndGet();
            resolvedChangeResults.put(key(toolName, input), result);
            completedChanges.add(new AiApprovedExecution(toolName, input, result));
        }

        public Optional<String> cachedResult(String toolName, String input) {
            return Optional.ofNullable(resolvedChangeResults.get(key(toolName, input)));
        }

        private String executionKey(ToolAuthorizationPolicy.Request authorization) {
            return key(authorization.tool().name(), authorization.arguments().json());
        }

        public boolean startChange(String toolName, String arguments) {
            return startChange(key(toolName, arguments));
        }

        private boolean startChange(String executionKey) {
            return changeLease.acquire(executionKey);
        }

        public void releaseChangeLease(String toolName, String arguments) {
            releaseChangeLease(key(toolName, arguments));
        }

        private void releaseChangeLease(ToolAuthorizationPolicy.Request authorization) {
            releaseChangeLease(executionKey(authorization));
        }

        private void releaseChangeLease(String executionKey) {
            changeLease.release(executionKey);
        }
    }

    public static String requestStoppingResult() {
        return REQUEST_STOPPING_RESULT;
    }

}

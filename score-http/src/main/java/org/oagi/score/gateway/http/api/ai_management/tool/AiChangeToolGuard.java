package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.model.AiChangeAuthorization;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangePermissionMode;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeRisk;

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
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolAuthorizationPolicy;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AiChangeOwnershipPolicy ownership;
    private final AiToolInputNormalizer inputNormalizer;
    private final ObjectMapper objectMapper;

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
        this.ownership = ownership;
        this.objectMapper = objectMapper;
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
            guarded[index] = new GuardedToolCallback(callbacks[index], request, requester,
                    notices, session);
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

    private final class GuardedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final ChatRequest request;
        private final ScoreUser requester;
        private final Consumer<AiChangeConfirmationNotice> notices;
        private final GuardedToolSession session;

        private GuardedToolCallback(ToolCallback delegate, ChatRequest request, ScoreUser requester,
                                    Consumer<AiChangeConfirmationNotice> notices,
                                    GuardedToolSession session) {
            this.delegate = delegate;
            this.request = request;
            this.requester = requester;
            this.notices = notices;
            this.session = session;
        }

        @Override
        public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }

        @Override
        public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }

        @Override
        public String call(String input) { return call(input, new ToolContext(java.util.Map.of())); }

        @Override
        public String call(String input, ToolContext context) {
            String name = getToolDefinition().name();
            String normalizedInput = inputNormalizer.normalize(
                    input, getToolDefinition().inputSchema());
            if (!session.isChange(name)) {
                String result = delegate.call(normalizedInput, context);
                session.readCompleted();
                return result;
            }
            Optional<String> cached = session.cachedResult(name, normalizedInput);
            if (cached.isPresent()) {
                return cached.get();
            }
            ChangeConfirmation supplied = session.redemption(name, normalizedInput)
                    .orElse(request.changeConfirmation());
            if (supplied != null || requiresApproval(request, requester, name, normalizedInput)) {
                AiChangeAuthorization authorization = confirmations.authorize(
                        requester, request.conversationId(), request.requestId(), supplied,
                        name, normalizedInput);
                if (!authorization.allowed()) {
                    session.markConfirmationRequired(authorization.notice(), name, normalizedInput);
                    notices.accept(authorization.notice());
                    return confirmationRequiredResult(
                            authorization.notice().confirmationRequestId());
                }
            }
            if (!session.startChange(name, normalizedInput)) {
                return REQUEST_STOPPING_RESULT;
            }
            try {
                String result = delegate.call(normalizedInput, context);
                session.changeCompleted(name, normalizedInput, result);
                return result;
            } finally {
                session.releaseChangeLease(name, normalizedInput);
            }
        }
    }

    /** Request-scoped tool state used to resume an exact approval and enforce read-back. */
    public final class GuardedToolSession implements ToolCallbackProvider, ToolAuthorizationPolicy {
        private final ChatRequest request;
        private final ScoreUser requester;
        private final Consumer<AiChangeConfirmationNotice> notices;
        private final ToolCallback[] callbacks;
        private final Set<String> readOnlyToolNames;
        private final WorkflowRunControl runControl;
        private final AtomicLong sequence = new AtomicLong();
        private final CopyOnWriteArrayList<AiApprovedExecution> completedChanges =
                new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<AiPendingChangeApproval> pendingApprovals =
                new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, ChangeConfirmation> redemptions =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> resolvedChangeResults =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> activeChangeLeases =
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
            this.runControl = Objects.requireNonNullElse(runControl, WorkflowRunControl.NOOP);
        }

        boolean isChange(String toolName) {
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
            if (supplied != null || requiresApproval(request, requester, name, normalizedInput)) {
                AiChangeAuthorization result = confirmations.authorize(
                        requester, request.conversationId(), request.requestId(), supplied,
                        name, normalizedInput);
                if (!result.allowed()) {
                    markConfirmationRequired(result.notice(), name, normalizedInput);
                    notices.accept(result.notice());
                    return new ToolAuthorizationPolicy.Result.Refuse(new AiTool.ToolResult(
                            confirmationRequiredResult(result.notice().confirmationRequestId())));
                }
            }
            return new ToolAuthorizationPolicy.Result.Allow(
                    supplied != null ? supplied.confirmationRequestId() : "permission-mode");
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
                        supplied.arguments(), changeFailedResult(failure)));
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
                            approval.arguments(), changeFailedResult(failure), false));
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

        private void markConfirmationRequired(
                AiChangeConfirmationNotice notice, String toolName, String arguments) {
            confirmationRequired = true;
            boolean recorded = pendingApprovals.stream().anyMatch(existing ->
                    existing.notice().confirmationRequestId().equals(notice.confirmationRequestId()));
            if (!recorded) {
                pendingApprovals.add(new AiPendingChangeApproval(notice, toolName, arguments));
            }
        }

        private Optional<ChangeConfirmation> redemption(String toolName, String arguments) {
            if (redemptions.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(redemptions.remove(key(toolName, arguments)));
        }

        private String changeFailedResult(RuntimeException failure) {
            String detail = failureDetail(failure);
            return objectMapper.createObjectNode()
                    .put("error", CHANGE_FAILED)
                    .put("message", StringUtils.hasText(detail)
                            ? detail : "The data-changing tool call did not complete.")
                    .toString();
        }

        private String failureDetail(Throwable failure) {
            for (Throwable candidate = failure; candidate != null; candidate = candidate.getCause()) {
                if (StringUtils.hasText(candidate.getMessage())) {
                    return candidate.getMessage();
                }
            }
            return null;
        }

        private String key(String toolName, String arguments) {
            String digest = confirmations.argumentsDigest(toolName, arguments);
            return StringUtils.hasText(digest)
                    ? digest : Objects.toString(toolName, "") + "\u0000"
                    + Objects.toString(arguments, "");
        }

        private void readCompleted() {
            lastReadSequence = sequence.incrementAndGet();
        }

        private void changeCompleted(String toolName, String input, String result) {
            lastChangeSequence = sequence.incrementAndGet();
            resolvedChangeResults.put(key(toolName, input), result);
            completedChanges.add(new AiApprovedExecution(toolName, input, result));
        }

        private Optional<String> cachedResult(String toolName, String input) {
            return Optional.ofNullable(resolvedChangeResults.get(key(toolName, input)));
        }

        private String executionKey(ToolAuthorizationPolicy.Request authorization) {
            return key(authorization.tool().name(), authorization.arguments().json());
        }

        private boolean startChange(String toolName, String arguments) {
            return startChange(key(toolName, arguments));
        }

        private boolean startChange(String executionKey) {
            runControl.definiteActivityStarted();
            boolean registered = false;
            boolean tracked = false;
            try {
                registered = requests.changeStarted(request.requestId());
                if (!registered) {
                    return false;
                }
                activeChangeLeases.compute(executionKey, (ignored, leases) -> {
                    AtomicInteger current = leases != null ? leases : new AtomicInteger();
                    current.incrementAndGet();
                    return current;
                });
                tracked = true;
                return true;
            } finally {
                if (!tracked) {
                    try {
                        if (registered) {
                            requests.changeFinished(request.requestId());
                        }
                    } finally {
                        runControl.definiteActivityFinished();
                    }
                }
            }
        }

        private void releaseChangeLease(String toolName, String arguments) {
            releaseChangeLease(key(toolName, arguments));
        }

        private void releaseChangeLease(ToolAuthorizationPolicy.Request authorization) {
            releaseChangeLease(executionKey(authorization));
        }

        private void releaseChangeLease(String executionKey) {
            java.util.concurrent.atomic.AtomicBoolean released =
                    new java.util.concurrent.atomic.AtomicBoolean();
            activeChangeLeases.computeIfPresent(executionKey, (ignored, leases) -> {
                released.set(true);
                return leases.decrementAndGet() <= 0 ? null : leases;
            });
            if (!released.get()) return;
            try {
                requests.changeFinished(request.requestId());
            } finally {
                runControl.definiteActivityFinished();
            }
        }
    }

    /**
     * Reports whether a data-changing tool call needs explicit approval under the request's
     * permission mode. Automatic mode skips approval for tools that only create new data, and
     * for tools that change one existing record while the requester owns that record; every
     * other data-changing tool, including deletions and state changes on the requester's own
     * data, is approved explicitly.
     */
    private boolean requiresApproval(ChatRequest request, ScoreUser requester,
                                     String toolName, String arguments) {
        AiChangePermissionMode permissionMode =
                AiChangePermissionMode.resolve(request.permissionMode());
        AiChangeRisk risk = AiChangeRiskCatalog.ruleOf(toolName).risk();
        if (permissionMode.automaticallyAllows(risk)) {
            return false;
        }
        return !permissionMode.requiresOwnershipCheck(risk)
                || !ownership.requesterOwnsTarget(requester, toolName, arguments);
    }

    private String confirmationRequiredResult(String confirmationRequestId) {
        var result = objectMapper.createObjectNode();
        result.put("error", CHANGE_CONFIRMATION_REQUIRED);
        result.put("message", "This data-changing tool call was not executed."
                + " Wait for explicit user approval.");
        result.put("confirmationRequestId", confirmationRequestId);
        return result.toString();
    }

}

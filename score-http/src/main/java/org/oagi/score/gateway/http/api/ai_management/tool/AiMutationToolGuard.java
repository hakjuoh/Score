package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.model.AiMutationAuthorization;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationPermissionMode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.model.AiApprovedExecution;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationApprovalResolution;
import org.oagi.score.gateway.http.api.ai_management.model.AiPendingMutationApproval;
import org.oagi.score.gateway.http.api.ai_management.model.AiResolvedMutation;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.MutationConfirmation;
import org.oagi.score.gateway.http.api.ai_management.service.AiMutationConfirmationService;
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
public class AiMutationToolGuard {

    public static final String MUTATION_CONFIRMATION_REQUIRED = "MUTATION_CONFIRMATION_REQUIRED";
    public static final String MUTATION_CONFIRMATION_DENIED = "MUTATION_CONFIRMATION_DENIED";
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
            "{\"error\":\"" + MUTATION_CONFIRMATION_DENIED
                    + "\",\"message\":\"The user denied this data-changing tool call.\"}";

    private final AiMutationConfirmationService confirmations;
    private final AiRequestRegistry requests;
    private final AiToolInputNormalizer inputNormalizer;
    private final ObjectMapper objectMapper;

    public AiMutationToolGuard(AiMutationConfirmationService confirmations, AiRequestRegistry requests) {
        this(confirmations, requests, new ObjectMapper());
    }

    @Autowired
    public AiMutationToolGuard(AiMutationConfirmationService confirmations, AiRequestRegistry requests,
                               ObjectMapper objectMapper) {
        this.confirmations = confirmations;
        this.requests = requests;
        this.objectMapper = objectMapper;
        this.inputNormalizer = new AiToolInputNormalizer(objectMapper);
    }

    public ToolCallbackProvider guard(ChatRequest request, ScoreUser requester,
                                      Consumer<AiMutationConfirmationNotice> noticeConsumer,
                                      ToolCallbackProvider delegate, Set<String> readOnlyToolNames) {
        return session(request, requester, noticeConsumer, delegate, readOnlyToolNames);
    }

    public GuardedToolSession session(ChatRequest request, ScoreUser requester,
                                      Consumer<AiMutationConfirmationNotice> noticeConsumer,
                                      ToolCallbackProvider delegate, Set<String> readOnlyToolNames) {
        ToolCallback[] callbacks = delegate != null ? delegate.getToolCallbacks() : new ToolCallback[0];
        ToolCallback[] guarded = new ToolCallback[callbacks.length];
        Set<String> emitted = ConcurrentHashMap.newKeySet();
        Consumer<AiMutationConfirmationNotice> notices = notice -> {
            if (emitted.add(notice.confirmationRequestId())) {
                noticeConsumer.accept(notice);
            }
        };
        GuardedToolSession session = new GuardedToolSession(
                request, requester, notices, guarded, readOnlyToolNames);
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
            Consumer<AiMutationConfirmationNotice> noticeConsumer,
            ToolCallbackProvider delegate, Set<String> readOnlyToolNames) {
        ToolCallback[] callbacks = delegate != null ? delegate.getToolCallbacks() : new ToolCallback[0];
        Set<String> emitted = ConcurrentHashMap.newKeySet();
        Consumer<AiMutationConfirmationNotice> notices = notice -> {
            if (emitted.add(notice.confirmationRequestId())) {
                noticeConsumer.accept(notice);
            }
        };
        return new GuardedToolSession(request, requester, notices,
                callbacks, readOnlyToolNames);
    }

    /** Returns only the tools the server declared read-only, fail-closed, for specialist agents. */
    public ToolCallbackProvider readOnly(ToolCallbackProvider delegate, Set<String> readOnlyToolNames) {
        ToolCallback[] callbacks = delegate != null ? delegate.getToolCallbacks() : new ToolCallback[0];
        ToolCallback[] readOnly = Arrays.stream(callbacks)
                .filter(callback -> !isMutation(callback.getToolDefinition().name(), readOnlyToolNames))
                .toArray(ToolCallback[]::new);
        return () -> readOnly;
    }

    static boolean isMutation(String toolName, Set<String> readOnlyToolNames) {
        return toolName == null || readOnlyToolNames == null
                || !readOnlyToolNames.contains(toolName);
    }

    private final class GuardedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final ChatRequest request;
        private final ScoreUser requester;
        private final Consumer<AiMutationConfirmationNotice> notices;
        private final GuardedToolSession session;

        private GuardedToolCallback(ToolCallback delegate, ChatRequest request, ScoreUser requester,
                                    Consumer<AiMutationConfirmationNotice> notices,
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
            if (!session.isMutation(name)) {
                String result = delegate.call(normalizedInput, context);
                session.readCompleted();
                return result;
            }
            Optional<String> cached = session.cachedResult(name, normalizedInput);
            if (cached.isPresent()) {
                return cached.get();
            }
            AiMutationPermissionMode permissionMode = AiMutationPermissionMode.resolve(request.permissionMode());
            MutationConfirmation supplied = session.redemption(name, normalizedInput)
                    .orElse(request.mutationConfirmation());
            if (supplied != null || !permissionMode.automaticallyAllows(name)) {
                AiMutationAuthorization authorization = confirmations.authorize(
                        requester, request.conversationId(), request.requestId(), supplied,
                        name, normalizedInput);
                if (!authorization.allowed()) {
                    session.markConfirmationRequired(authorization.notice(), name, normalizedInput);
                    notices.accept(authorization.notice());
                    return confirmationRequiredResult(
                            authorization.notice().confirmationRequestId());
                }
            }
            if (!requests.mutationStarted(request.requestId())) {
                return REQUEST_STOPPING_RESULT;
            }
            try {
                String result = delegate.call(normalizedInput, context);
                session.mutationCompleted(name, normalizedInput, result);
                return result;
            } finally {
                requests.mutationFinished(request.requestId());
            }
        }
    }

    /** Request-scoped tool state used to resume an exact approval and enforce read-back. */
    public final class GuardedToolSession implements ToolCallbackProvider, ToolAuthorizationPolicy {
        private final ChatRequest request;
        private final ScoreUser requester;
        private final Consumer<AiMutationConfirmationNotice> notices;
        private final ToolCallback[] callbacks;
        private final Set<String> readOnlyToolNames;
        private final AtomicLong sequence = new AtomicLong();
        private final CopyOnWriteArrayList<AiApprovedExecution> completedMutations =
                new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<AiPendingMutationApproval> pendingApprovals =
                new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, MutationConfirmation> redemptions =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> resolvedMutationResults =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> activeMutationLeases =
                new ConcurrentHashMap<>();
        private volatile long lastMutationSequence = -1L;
        private volatile long lastReadSequence = -1L;
        private volatile boolean confirmationRequired;

        private GuardedToolSession(ChatRequest request, ScoreUser requester,
                                   Consumer<AiMutationConfirmationNotice> notices,
                                   ToolCallback[] callbacks,
                                   Set<String> readOnlyToolNames) {
            this.request = request;
            this.requester = requester;
            this.notices = notices;
            this.callbacks = callbacks;
            this.readOnlyToolNames = readOnlyToolNames != null ? Set.copyOf(readOnlyToolNames) : Set.of();
        }

        boolean isMutation(String toolName) {
            return AiMutationToolGuard.isMutation(toolName, readOnlyToolNames);
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
            if (!isMutation(name)) {
                return new ToolAuthorizationPolicy.Result.Allow("read-only");
            }
            Optional<String> cached = cachedResult(name, normalizedInput);
            if (cached.isPresent()) {
                return new ToolAuthorizationPolicy.Result.Refuse(
                        new AiTool.ToolResult(cached.get()));
            }
            AiMutationPermissionMode permissionMode =
                    AiMutationPermissionMode.resolve(request.permissionMode());
            MutationConfirmation supplied = redemption(name, normalizedInput)
                    .orElse(request.mutationConfirmation());
            if (supplied != null || !permissionMode.automaticallyAllows(name)) {
                AiMutationAuthorization result = confirmations.authorize(
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
            if (!isMutation(authorization.tool().name())) {
                return new ToolAuthorizationPolicy.Result.Allow("read-only");
            }
            if (!requests.mutationStarted(request.requestId())) {
                return new ToolAuthorizationPolicy.Result.Refuse(
                        new AiTool.ToolResult(REQUEST_STOPPING_RESULT));
            }
            activeMutationLeases.computeIfAbsent(executionKey(authorization),
                    ignored -> new AtomicInteger()).incrementAndGet();
            return new ToolAuthorizationPolicy.Result.Allow("mutation-fenced");
        }

        @Override
        public void afterExecution(ToolAuthorizationPolicy.Request authorization,
                                   AiTool.ToolResult result) {
            if (isMutation(authorization.tool().name())) {
                try {
                    mutationCompleted(authorization.tool().name(),
                            authorization.arguments().json(), result.json());
                } finally {
                    releaseMutationLease(authorization);
                }
            } else {
                readCompleted();
            }
        }

        @Override
        public void afterFailure(ToolAuthorizationPolicy.Request authorization,
                                 RuntimeException failure) {
            if (isMutation(authorization.tool().name())) {
                releaseMutationLease(authorization);
            }
        }

        @Override
        public void afterAborted(ToolAuthorizationPolicy.Request authorization) {
            if (isMutation(authorization.tool().name())) {
                releaseMutationLease(authorization);
            }
        }

        public Optional<AiApprovedExecution> executeApproved(ToolCallbackProvider executableTools) {
            var supplied = request.mutationConfirmation();
            if (supplied == null || supplied.revised()
                    || !StringUtils.hasText(supplied.toolName())
                    || !StringUtils.hasText(supplied.arguments())) {
                return Optional.empty();
            }
            if (!isMutation(supplied.toolName())) {
                throw new IllegalArgumentException("An approved mutation must target a data-changing tool.");
            }
            ToolCallback callback = Arrays.stream(executableTools.getToolCallbacks())
                    .filter(candidate -> supplied.toolName().equals(candidate.getToolDefinition().name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The approved mutation tool is not available: " + supplied.toolName()));
            long mutationsBefore = lastMutationSequence;
            String result = callback.call(supplied.arguments(), new ToolContext(Map.of()));
            if (lastMutationSequence == mutationsBefore) {
                return Optional.empty();
            }
            AiApprovedExecution execution = new AiApprovedExecution(
                    supplied.toolName(), supplied.arguments(), result);
            return Optional.of(execution);
        }

        public List<AiPendingMutationApproval> pendingApprovals() {
            return List.copyOf(pendingApprovals);
        }

        /** Executes approved invocations through the normal recording callbacks and resolves denials. */
        public List<AiResolvedMutation> resolveApprovals(
                ToolCallbackProvider executableTools,
                Map<String, AiMutationApprovalResolution> resolutions) {
            if (pendingApprovals.isEmpty()) {
                return List.of();
            }
            List<AiPendingMutationApproval> wave = List.copyOf(pendingApprovals);
            pendingApprovals.clear();
            List<AiResolvedMutation> resolved = new java.util.ArrayList<>(wave.size());
            for (AiPendingMutationApproval approval : wave) {
                AiMutationApprovalResolution resolution = resolutions.get(
                        approval.notice().confirmationRequestId());
                if (resolution == null || !resolution.approved()) {
                    resolvedMutationResults.put(
                            key(approval.toolName(), approval.arguments()),
                            CONFIRMATION_DENIED_RESULT);
                    resolved.add(new AiResolvedMutation(approval.toolName(), approval.arguments(),
                            CONFIRMATION_DENIED_RESULT, false));
                    continue;
                }
                MutationConfirmation authorization = new MutationConfirmation(
                        approval.notice().confirmationRequestId(), resolution.confirmationGrant(),
                        approval.toolName(), approval.arguments());
                redemptions.put(key(approval.toolName(), approval.arguments()), authorization);
                ToolCallback callback = Arrays.stream(executableTools.getToolCallbacks())
                        .filter(candidate -> approval.toolName().equals(
                                candidate.getToolDefinition().name()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "The approved mutation tool is not available: " + approval.toolName()));
                long mutationsBefore = lastMutationSequence;
                String result = callback.call(approval.arguments(), new ToolContext(Map.of()));
                if (lastMutationSequence == mutationsBefore) {
                    throw new IllegalStateException(
                            "An approved mutation could not be executed: " + approval.toolName());
                }
                resolved.add(new AiResolvedMutation(
                        approval.toolName(), approval.arguments(), result, true));
            }
            confirmationRequired = !pendingApprovals.isEmpty();
            return List.copyOf(resolved);
        }

        public boolean mutationCompleted() {
            return lastMutationSequence >= 0L;
        }

        public boolean readAfterLastMutation() {
            return lastMutationSequence >= 0L && lastReadSequence > lastMutationSequence;
        }

        public boolean confirmationRequired() {
            return confirmationRequired;
        }

        public List<AiApprovedExecution> completedMutations() {
            return List.copyOf(completedMutations);
        }

        private void markConfirmationRequired(
                AiMutationConfirmationNotice notice, String toolName, String arguments) {
            confirmationRequired = true;
            boolean recorded = pendingApprovals.stream().anyMatch(existing ->
                    existing.notice().confirmationRequestId().equals(notice.confirmationRequestId()));
            if (!recorded) {
                pendingApprovals.add(new AiPendingMutationApproval(notice, toolName, arguments));
            }
        }

        private Optional<MutationConfirmation> redemption(String toolName, String arguments) {
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

        private void readCompleted() {
            lastReadSequence = sequence.incrementAndGet();
        }

        private void mutationCompleted(String toolName, String input, String result) {
            lastMutationSequence = sequence.incrementAndGet();
            resolvedMutationResults.put(key(toolName, input), result);
            completedMutations.add(new AiApprovedExecution(toolName, input, result));
        }

        private Optional<String> cachedResult(String toolName, String input) {
            return Optional.ofNullable(resolvedMutationResults.get(key(toolName, input)));
        }

        private String executionKey(ToolAuthorizationPolicy.Request authorization) {
            return key(authorization.tool().name(), authorization.arguments().json());
        }

        private void releaseMutationLease(ToolAuthorizationPolicy.Request authorization) {
            String key = executionKey(authorization);
            AtomicInteger leases = activeMutationLeases.get(key);
            if (leases == null) {
                return;
            }
            int remaining = leases.decrementAndGet();
            if (remaining <= 0) {
                activeMutationLeases.remove(key, leases);
            }
            requests.mutationFinished(request.requestId());
        }
    }

    private String confirmationRequiredResult(String confirmationRequestId) {
        var result = objectMapper.createObjectNode();
        result.put("error", MUTATION_CONFIRMATION_REQUIRED);
        result.put("message", "This data-changing tool call was not executed."
                + " Wait for explicit user approval.");
        result.put("confirmationRequestId", confirmationRequestId);
        return result.toString();
    }

}

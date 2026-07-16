package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.model.AiMutationAuthorization;
import org.oagi.score.gateway.http.api.ai_management.model.AiMutationConfirmationNotice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
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
import java.util.function.Consumer;

/** Enforces explicit, one-time approval before any non-allowlisted connectCenter MCP tool call. */
@Component
public class AiMutationToolGuard {

    /*
     * MCP's Spring AI adapter does not expose MCP readOnlyHint metadata. Keep an
     * exact, case-sensitive allowlist of the current connectCenter read tools so
     * a future data-changing tool cannot bypass approval merely by using a
     * get_* prefix. Every unknown/future name requires approval by default.
     */
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "get_agency_id_lists",
            "get_agency_id_list",
            "get_users",
            "who_am_i",
            "get_business_contexts",
            "get_business_context",
            "get_top_level_asbiep_list",
            "get_top_level_asbiep",
            "get_asbie_by_asbie_id",
            "get_asbie_by_based_ascc_manifest_id",
            "get_bbie_by_bbie_id",
            "get_bbie_by_based_bcc_manifest_id",
            "get_code_lists",
            "get_code_list",
            "get_core_components",
            "get_acc",
            "get_asccp",
            "get_bccp",
            "get_context_categories",
            "get_context_category",
            "get_context_schemes",
            "get_context_scheme",
            "get_data_types",
            "get_data_type",
            "get_libraries",
            "get_library",
            "get_namespaces",
            "get_namespace",
            "get_releases",
            "get_release",
            "get_working_release",
            "get_tags",
            "get_xbt");

    private final AiMutationConfirmationService confirmations;
    private final AiRequestRegistry requests;
    private final AiToolInputNormalizer inputNormalizer;

    public AiMutationToolGuard(AiMutationConfirmationService confirmations, AiRequestRegistry requests) {
        this(confirmations, requests, new ObjectMapper());
    }

    @Autowired
    public AiMutationToolGuard(AiMutationConfirmationService confirmations, AiRequestRegistry requests,
                               ObjectMapper objectMapper) {
        this.confirmations = confirmations;
        this.requests = requests;
        this.inputNormalizer = new AiToolInputNormalizer(objectMapper);
    }

    public ToolCallbackProvider guard(ChatRequest request, ScoreUser requester,
                                      Consumer<AiMutationConfirmationNotice> noticeConsumer,
                                      ToolCallbackProvider delegate) {
        return session(request, requester, noticeConsumer, delegate);
    }

    public GuardedToolSession session(ChatRequest request, ScoreUser requester,
                                      Consumer<AiMutationConfirmationNotice> noticeConsumer,
                                      ToolCallbackProvider delegate) {
        ToolCallback[] callbacks = delegate != null ? delegate.getToolCallbacks() : new ToolCallback[0];
        ToolCallback[] guarded = new ToolCallback[callbacks.length];
        GuardedToolSession session = new GuardedToolSession(request, guarded);
        Set<String> emitted = ConcurrentHashMap.newKeySet();
        for (int index = 0; index < callbacks.length; index++) {
            guarded[index] = new GuardedToolCallback(callbacks[index], request, requester,
                    notice -> {
                        if (emitted.add(notice.confirmationRequestId())) {
                            noticeConsumer.accept(notice);
                        }
                    }, session);
        }
        return session;
    }

    boolean isMutation(String toolName) {
        return toolName == null || !READ_ONLY_TOOLS.contains(toolName);
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
            if (!isMutation(name)) {
                String result = delegate.call(normalizedInput, context);
                session.readCompleted();
                return result;
            }
            Optional<String> cached = session.cachedResult(name, normalizedInput);
            if (cached.isPresent()) {
                return cached.get();
            }
            AiMutationPermissionMode permissionMode = AiMutationPermissionMode.resolve(request.permissionMode());
            if (request.mutationConfirmation() != null || !permissionMode.automaticallyAllows(name)) {
                AiMutationAuthorization authorization = confirmations.authorize(
                        requester, request.conversationId(), request.requestId(), request.mutationConfirmation(),
                        name, normalizedInput);
                if (!authorization.allowed()) {
                    session.markConfirmationRequired();
                    notices.accept(authorization.notice());
                    return "{\"error\":\"MUTATION_CONFIRMATION_REQUIRED\","
                            + "\"message\":\"This data-changing tool call was not executed. Wait for explicit user approval.\"}";
                }
            }
            if (!requests.mutationStarted(request.requestId())) {
                return "{\"error\":\"REQUEST_STOPPING\","
                        + "\"message\":\"The request is stopping; the data-changing tool was not executed.\"}";
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
    public final class GuardedToolSession implements ToolCallbackProvider {
        private final ChatRequest request;
        private final ToolCallback[] callbacks;
        private final AtomicLong sequence = new AtomicLong();
        private final CopyOnWriteArrayList<ApprovedExecution> completedMutations =
                new CopyOnWriteArrayList<>();
        private volatile long lastMutationSequence = -1L;
        private volatile long lastReadSequence = -1L;
        private volatile boolean confirmationRequired;
        private volatile ApprovedExecution approvedExecution;

        private GuardedToolSession(ChatRequest request, ToolCallback[] callbacks) {
            this.request = request;
            this.callbacks = callbacks;
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            return callbacks;
        }

        public Optional<ApprovedExecution> executeApproved(ToolCallbackProvider executableTools) {
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
            ApprovedExecution execution = new ApprovedExecution(
                    supplied.toolName(), supplied.arguments(), result);
            approvedExecution = execution;
            return Optional.of(execution);
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

        public List<ApprovedExecution> completedMutations() {
            return List.copyOf(completedMutations);
        }

        private void markConfirmationRequired() {
            confirmationRequired = true;
        }

        private void readCompleted() {
            lastReadSequence = sequence.incrementAndGet();
        }

        private void mutationCompleted(String toolName, String input, String result) {
            lastMutationSequence = sequence.incrementAndGet();
            approvedExecution = new ApprovedExecution(toolName, input, result);
            completedMutations.add(approvedExecution);
        }

        private Optional<String> cachedResult(String toolName, String input) {
            ApprovedExecution execution = approvedExecution;
            return execution != null && execution.toolName().equals(toolName)
                    && Objects.equals(confirmations.argumentsDigest(toolName, execution.arguments()),
                    confirmations.argumentsDigest(toolName, input))
                    ? Optional.of(execution.result()) : Optional.empty();
        }
    }

    public record ApprovedExecution(String toolName, String arguments, String result) {}
}

package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable state for one active observation turn; all terminal transitions synchronize here. */
final class AiTurnState {

    static final ContextKey<String> ACTIVE_AGENT_RUN_ID =
            ContextKey.named("score.ai.active-agent-run-id");

    final String requestId;
    final long generation;
    volatile String model;
    final String reasoningLevel;
    volatile String conversationId;
    final String workflowName;
    final Span span;
    final Context context;
    final long startedNanos;
    final Attributes activeRequestAttributes;
    final AtomicLong modelCalls = new AtomicLong();
    final AtomicLong rootInputTokens = new AtomicLong();
    final AtomicLong rootOutputTokens = new AtomicLong();
    final AtomicLong rootCacheReadTokens = new AtomicLong();
    final AtomicBoolean rootCacheReadObserved = new AtomicBoolean();
    final AtomicLong rootCacheCreationTokens = new AtomicLong();
    final AtomicBoolean rootCacheCreationObserved = new AtomicBoolean();
    final AtomicLong rootFinishReasonSequence = new AtomicLong();
    volatile List<String> rootFinishReasons = List.of();
    final ConcurrentMap<String, ModelSequence> modelSequences = new ConcurrentHashMap<>();
    final Set<AiModelCallTelemetry> activeModelCalls = new HashSet<>();
    final ConcurrentMap<String, Context> agentContexts = new ConcurrentHashMap<>();
    final ConcurrentMap<String, AgentInvocation> agentInvocations = new ConcurrentHashMap<>();
    final ConcurrentMap<String, Context> agentNodeContexts = new ConcurrentHashMap<>();
    final Set<AiPlanOperationTelemetry> activeOperations = new HashSet<>();
    final AtomicBoolean firstToken = new AtomicBoolean();
    final AtomicBoolean executionStarted = new AtomicBoolean();
    final AtomicBoolean compacted = new AtomicBoolean();
    final AtomicBoolean ended;
    final AtomicBoolean closing = new AtomicBoolean();
    private final ConcurrentMap<String, AiTurnState> registry;
    private int activePublications;

    AiTurnState(String requestId, String conversationId, long generation, String model,
                String reasoningLevel, String workflowName, Span span, Context context,
                long startedNanos, AtomicBoolean ended,
                ConcurrentMap<String, AiTurnState> registry) {
        this.requestId = requestId;
        this.generation = generation;
        this.conversationId = StringUtils.hasText(conversationId)
                ? conversationId.strip() : null;
        this.model = model;
        this.reasoningLevel = reasoningLevel;
        this.workflowName = workflowName;
        this.span = span;
        this.context = context;
        this.startedNanos = startedNanos;
        this.ended = ended;
        this.registry = registry;
        this.activeRequestAttributes = AiObservationLabels.model(model, null);
    }

    synchronized boolean reservePublication() {
        if (ended.get() || closing.get() || registry.get(requestId) != this) return false;
        activePublications++;
        return true;
    }

    synchronized void releasePublication() {
        activePublications--;
        if (activePublications == 0) notifyAll();
    }

    synchronized boolean beginClosing() {
        if (!closing.compareAndSet(false, true)) return false;
        boolean interrupted = false;
        while (activePublications > 0) {
            try {
                wait();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return true;
    }

    ModelIdentity nextModelIdentity(Context parent) {
        long sequence = modelCalls.incrementAndGet();
        Optional<AgentInvocation> invocation = agentInvocation(parent);
        invocation.ifPresent(active -> active.inferenceCalls.incrementAndGet());
        String parentSpanId = Span.fromContext(parent).getSpanContext().isValid()
                ? Span.fromContext(parent).getSpanContext().getSpanId() : "root";
        return modelSequences.computeIfAbsent(parentSpanId, ignored -> new ModelSequence())
                .next(invocation.orElse(null), sequence);
    }

    void recordFinishReasons(long sequence, List<String> reasons) {
        if (sequence >= rootFinishReasonSequence.getAndAccumulate(sequence, Math::max)) {
            rootFinishReasons = List.copyOf(reasons);
        }
    }

    void recordToolCall(Context parent) {
        agentInvocation(parent).ifPresent(invocation -> invocation.toolCalls.incrementAndGet());
    }

    void retryScheduled(Context parent) {
        String parentSpanId = Span.fromContext(parent).getSpanContext().isValid()
                ? Span.fromContext(parent).getSpanContext().getSpanId() : "root";
        modelSequences.computeIfAbsent(parentSpanId, ignored -> new ModelSequence())
                .retryScheduled();
    }

    private Optional<AgentInvocation> agentInvocation(Context parent) {
        String activeRunId = parent.get(ACTIVE_AGENT_RUN_ID);
        AgentInvocation attributed = activeRunId != null
                ? agentInvocations.get(activeRunId) : null;
        if (attributed != null) return Optional.of(attributed);
        String parentSpanId = Span.fromContext(parent).getSpanContext().getSpanId();
        return agentInvocations.values().stream().filter(invocation ->
                Span.fromContext(invocation.context).getSpanContext().getSpanId()
                        .equals(parentSpanId)).findFirst();
    }

    static final class AgentInvocation {
        final String agentName;
        final String workflowNodeId;
        final Context context;
        final AtomicLong inferenceCalls = new AtomicLong();
        final AtomicLong toolCalls = new AtomicLong();
        final AtomicLong inputTokens = new AtomicLong();
        final AtomicLong outputTokens = new AtomicLong();
        final AtomicLong cacheReadTokens = new AtomicLong();
        final AtomicBoolean cacheReadObserved = new AtomicBoolean();
        final AtomicLong cacheCreationTokens = new AtomicLong();
        final AtomicBoolean cacheCreationObserved = new AtomicBoolean();
        private final AtomicLong finishReasonSequence = new AtomicLong();
        private volatile List<String> finishReasons = List.of();

        AgentInvocation(String agentName, String workflowNodeId, Context context) {
            this.agentName = agentName;
            this.workflowNodeId = workflowNodeId;
            this.context = context;
        }

        ScoreAiObservability.AgentInvocationCounts snapshot() {
            return new ScoreAiObservability.AgentInvocationCounts(
                    inferenceCalls.get(), toolCalls.get(),
                    new ScoreAiObservability.AgentUsage(inputTokens.get(), outputTokens.get(),
                            cacheReadTokens.get(), cacheReadObserved.get(),
                            cacheCreationTokens.get(), cacheCreationObserved.get()),
                    List.copyOf(finishReasons));
        }

        void recordFinishReasons(long sequence, List<String> reasons) {
            if (sequence >= finishReasonSequence.getAndAccumulate(sequence, Math::max)) {
                finishReasons = List.copyOf(reasons);
            }
        }
    }

    record ModelIdentity(String callId, long attempt,
                         AgentInvocation agentInvocation, long sequence) { }

    private static final class ModelSequence {
        private String callId;
        private long attempt;
        private boolean retry;

        private synchronized ModelIdentity next(AgentInvocation invocation, long sequence) {
            if (!retry || callId == null) {
                callId = UUID.randomUUID().toString();
                attempt = 1L;
            } else {
                attempt++;
            }
            retry = false;
            return new ModelIdentity(callId, attempt, invocation, sequence);
        }

        private synchronized void retryScheduled() {
            retry = true;
        }
    }
}

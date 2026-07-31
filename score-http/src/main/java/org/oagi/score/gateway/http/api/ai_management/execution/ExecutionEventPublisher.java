package org.oagi.score.gateway.http.api.ai_management.execution;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The sole fan-out boundary for AI execution events. A request is serialized here so durable
 * storage, OpenTelemetry, and WebHook listeners observe the same identity and total order.
 */
@Component
@Order(0)
public final class ExecutionEventPublisher implements ExecutionObserver {

    public static final String EVENT_ID = "score.event.id";
    public static final String EVENT_SEQUENCE = "score.event.sequence";
    public static final String EVENT_OCCURRED_AT = "score.event.occurred_at";
    public static final String REQUEST_CLOSED = "request.closed";

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionEventPublisher.class);
    private static final ThreadLocal<Boolean> FANOUT_ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> TRANSACTION_COMPLETED = new ThreadLocal<>();

    private final List<ExecutionEventListener> causalListeners;
    private final List<ExecutionEventListener> externalListeners;
    private final TransactionTemplate transactions;
    private final ThreadPoolExecutor externalDrains;
    private final ConcurrentHashMap<String, RequestStream> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeStreamKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> closedStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> closedRequestIds = new ConcurrentHashMap<>();

    @Autowired
    public ExecutionEventPublisher(ObjectProvider<ExecutionEventListener> listeners,
                                   ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this(listeners != null ? listeners.orderedStream().toList() : List.of(),
                transactionManagers != null ? transactionManagers.getIfAvailable() : null, true);
    }

    ExecutionEventPublisher(List<ExecutionEventListener> listeners) {
        this(listeners, null, false);
    }

    ExecutionEventPublisher(List<ExecutionEventListener> listeners,
                            boolean asynchronousExternalDrain) {
        this(listeners, null, asynchronousExternalDrain);
    }

    private ExecutionEventPublisher(List<ExecutionEventListener> listeners,
                                    PlatformTransactionManager transactionManager,
                                    boolean asynchronousExternalDrain) {
        List<ExecutionEventListener> installed = listeners != null
                ? listeners.stream().filter(java.util.Objects::nonNull)
                .filter(ExecutionEventListener::enabled).toList() : List.of();
        this.causalListeners = installed.stream().filter(ExecutionEventListener::causal).toList();
        this.externalListeners = installed.stream().filter(listener -> !listener.causal()).toList();
        this.transactions = transactionManager != null
                ? new TransactionTemplate(transactionManager) : null;
        this.externalDrains = asynchronousExternalDrain && !externalListeners.isEmpty()
                ? new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(4096), runnable ->
                        Thread.ofVirtual().name("score-ai-event-external").unstarted(runnable),
                        ExecutionEventPublisher::enqueueWithBackpressure) : null;
    }

    private static void enqueueWithBackpressure(Runnable task, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("AI execution external drain is shut down");
        }
        try {
            executor.getQueue().put(task);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException(
                    "Interrupted while applying AI external event backpressure", interrupted);
        }
    }

    public static ExecutionEventPublisher forListeners(List<ExecutionEventListener> listeners) {
        return new ExecutionEventPublisher(listeners);
    }

    /** Runs terminal publication after Spring has chosen the transaction's final outcome. */
    public static void runAfterTransactionCompletion(Runnable action) {
        Boolean previous = TRANSACTION_COMPLETED.get();
        TRANSACTION_COMPLETED.set(true);
        try {
            action.run();
        } finally {
            if (previous == null) TRANSACTION_COMPLETED.remove();
            else TRANSACTION_COMPLETED.set(previous);
        }
    }

    @Override
    public void observe(ExecutionObservation observation) {
        publish(observation, ignored -> { });
    }

    @Override
    public void publish(ExecutionObservation observation,
                        Consumer<ExecutionObservation> durableWrite) {
        publish(observation, durableWrite, ignored -> { });
    }

    @Override
    public void publish(ExecutionObservation observation,
                        Consumer<ExecutionObservation> durableWrite,
                        Consumer<ExecutionObservation> orderedProjection) {
        java.util.Objects.requireNonNull(observation, "observation");
        Consumer<ExecutionObservation> persistence = durableWrite != null
                ? durableWrite : ignored -> { };
        Consumer<ExecutionObservation> projection = orderedProjection != null
                ? orderedProjection : ignored -> { };
        String requestId = observation.scope().requestId();
        boolean rootStart = "workflow.root.started".equals(observation.type());
        boolean rootRejection = "workflow.root.rejected".equals(observation.type());
        long generation = observation.scope().generation();
        String key = streamKey(observation);
        if (closedStreams.containsKey(key)) return;
        if (generation > 0L && !rootStart && !rootRejection && !setupEvent(observation)
                && !key.equals(activeStreamKeys.get(requestId)) && !streams.containsKey(key)) {
            return;
        }
        if (generation == 0L
                && activeStreamKeys.get(requestId) == null
                && closedRequestIds.containsKey(requestId)
                && !setupEvent(observation) && !rootStart && !rootRejection) return;
        RequestStream stream = streams.computeIfAbsent(key, ignored -> new RequestStream());
        if (rootStart) {
            activeStreamKeys.put(requestId, key);
            String provisionalKey = provisionalKey(requestId);
            if (!provisionalKey.equals(key)) {
                RequestStream provisional = streams.remove(provisionalKey);
                if (provisional != null && provisional != stream) {
                    synchronized (provisional) {
                        synchronized (stream) {
                            stream.beforeRoot.addAll(provisional.beforeRoot);
                            provisional.beforeRoot.clear();
                        }
                    }
                }
            }
        }
        List<Publication> buffered = List.of();
        synchronized (stream) {
            if (stream.closed || alreadyClosed(observation)) return;
            if (!stream.started && setupEvent(observation)) {
                stream.beforeRoot.addLast(new Publication(observation, persistence, projection));
                return;
            }
            if ("workflow.root.started".equals(observation.type())) {
                stream.started = true;
                buffered = List.copyOf(stream.beforeRoot);
                stream.beforeRoot.clear();
            } else if ("workflow.root.rejected".equals(observation.type())) {
                stream.started = true;
                stream.beforeRoot.clear();
            } else if (!stream.started) {
                stream.started = true;
            }
        }
        List<Publication> setup = buffered;
        Runnable batch = () -> {
            publishNow(key, stream, observation, persistence, projection);
            for (Publication pending : setup) {
                ExecutionObservation setupObservation = observation.scope().generation() > 0L
                        ? withGeneration(pending.observation(), observation.scope().generation())
                        : pending.observation();
                publishNow(key, stream, setupObservation, pending.persistence(),
                        pending.projection());
            }
        };
        if (!setup.isEmpty() && transactions != null) {
            transactions.executeWithoutResult(ignored -> batch.run());
        } else {
            batch.run();
        }
    }

    private String streamKey(ExecutionObservation observation) {
        String requestId = observation.scope().requestId();
        long generation = observation.scope().generation();
        if (generation > 0L) return requestId + "#" + generation;
        return activeStreamKeys.getOrDefault(requestId, provisionalKey(requestId));
    }

    private String provisionalKey(String requestId) {
        return requestId + "#pending";
    }

    private ExecutionObservation withGeneration(ExecutionObservation observation,
                                                long generation) {
        var source = observation.scope();
        var scope = new org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope(
                source.requestId(), source.conversationId(), source.requesterId(), generation,
                source.purpose(), source.guardrailDecisionIds());
        return new ExecutionObservation(observation.type(), scope, observation.occurredAt(),
                observation.attributes());
    }

    private boolean setupEvent(ExecutionObservation observation) {
        return "trajectory.settings_change".equals(observation.type())
                || "trajectory.workflow_preference".equals(observation.type());
    }

    private void publishNow(String key, RequestStream stream, ExecutionObservation observation,
                            Consumer<ExecutionObservation> persistence,
                            Consumer<ExecutionObservation> projection) {
        Dispatch dispatch;
        synchronized (stream) {
            if (stream.closed || alreadyClosed(observation)) return;
            if (stream.closingSequence > 0L) {
                stream.whileClosing.addLast(new Publication(
                        observation, persistence, projection));
                return;
            }
            Instant previousOccurredAt = stream.lastOccurredAt;
            ExecutionObservation active = withActiveGeneration(observation);
            ExecutionEventCanonicalizer.CanonicalEvent canonical =
                    ExecutionEventCanonicalizer.canonicalize(
                            active, stream.sequence.incrementAndGet(), stream.lastOccurredAt);
            ExecutionObservation event = canonical.observation();
            stream.lastOccurredAt = canonical.occurredAt();
            try {
                persistence.accept(event);
            } catch (RuntimeException | Error failure) {
                stream.sequence.decrementAndGet();
                stream.lastOccurredAt = previousOccurredAt;
                throw failure;
            }
            dispatch = new Dispatch(event, projection, new CompletableFuture<>());
            if (REQUEST_CLOSED.equals(event.type())) {
                stream.closingSequence = dispatch.sequence();
            }
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()
                && !Boolean.TRUE.equals(TRANSACTION_COMPLETED.get())
                && !Boolean.TRUE.equals(FANOUT_ACTIVE.get())) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            commitDispatch(key, stream, dispatch);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) {
                                skipDispatch(key, stream, dispatch.sequence());
                            }
                        }
                    });
            return;
        }
        commitDispatch(key, stream, dispatch);
    }

    private void commitDispatch(String key, RequestStream stream, Dispatch dispatch) {
        boolean drain;
        synchronized (stream) {
            stream.pending.put(dispatch.sequence(), dispatch);
            if (REQUEST_CLOSED.equals(dispatch.event().type())) {
                stream.closed = true;
                stream.closingSequence = 0L;
                stream.whileClosing.clear();
                String requestId = dispatch.event().scope().requestId();
                Instant closedAt = dispatch.event().occurredAt();
                closedStreams.put(key, closedAt);
                closedRequestIds.put(requestId, closedAt);
                activeStreamKeys.remove(requestId, key);
                streams.remove(key, stream);
                purgeClosedRequests();
            }
            drain = !stream.draining;
            if (drain) stream.draining = true;
        }
        if (drain) startDrain(stream);
        if (!Boolean.TRUE.equals(FANOUT_ACTIVE.get())) {
            dispatch.causalCompletion().join();
        }
    }

    private void skipDispatch(String key, RequestStream stream, long sequence) {
        boolean drain;
        List<Publication> replay = List.of();
        synchronized (stream) {
            stream.skipped.add(sequence);
            if (stream.closingSequence == sequence) {
                stream.closingSequence = 0L;
                replay = List.copyOf(stream.whileClosing);
                stream.whileClosing.clear();
            }
            drain = !stream.draining;
            if (drain) stream.draining = true;
        }
        if (drain) startDrain(stream);
        if (!replay.isEmpty()) {
            List<Publication> pendingReplay = replay;
            Runnable replayTask = () -> {
                FANOUT_ACTIVE.set(true);
                try {
                    for (Publication pending : pendingReplay) {
                        publishNow(key, stream, pending.observation(), pending.persistence(),
                                pending.projection());
                    }
                } finally {
                    FANOUT_ACTIVE.remove();
                }
            };
            replayTask.run();
        }
    }

    private void drain(RequestStream stream) {
        FANOUT_ACTIVE.set(true);
        try {
            while (true) {
                Dispatch dispatch;
                synchronized (stream) {
                    while (stream.skipped.remove(stream.nextDispatchSequence)) {
                        stream.nextDispatchSequence++;
                    }
                    dispatch = stream.pending.remove(stream.nextDispatchSequence);
                    if (dispatch == null) {
                        stream.draining = false;
                        return;
                    }
                    stream.nextDispatchSequence++;
                }
                try {
                    causalListeners.forEach(listener -> notify(listener, dispatch.event()));
                    try {
                        dispatch.projection().accept(dispatch.event());
                    } catch (RuntimeException failure) {
                        LOGGER.warn("AI execution ordered projection failed for request {} event {}",
                                dispatch.event().scope().requestId(),
                                dispatch.event().attributes().get(EVENT_ID), failure);
                    }
                    enqueueExternal(dispatch.event());
                } finally {
                    dispatch.causalCompletion().complete(null);
                }
            }
        } finally {
            FANOUT_ACTIVE.remove();
        }
    }

    private void startDrain(RequestStream stream) {
        drain(stream);
    }

    private void enqueueExternal(ExecutionObservation event) {
        if (externalListeners.isEmpty()) return;
        if (externalDrains == null) {
            externalListeners.forEach(listener -> notify(listener, event));
            return;
        }
        try {
            externalDrains.execute(() ->
                    externalListeners.forEach(listener -> notify(listener, event)));
        } catch (RejectedExecutionException rejected) {
            if (externalDrains.isShutdown()) {
                LOGGER.warn("AI execution external event {} was rejected during shutdown",
                        event.attributes().get(EVENT_ID));
            } else {
                LOGGER.error("AI execution external event {} could not be queued",
                        event.attributes().get(EVENT_ID), rejected);
            }
        }
    }

    private ExecutionObservation withActiveGeneration(ExecutionObservation observation) {
        if (observation.scope().generation() > 0L) return observation;
        String key = activeStreamKeys.get(observation.scope().requestId());
        if (key == null) return observation;
        int separator = key.lastIndexOf('#');
        if (separator < 0 || separator == key.length() - 1) return observation;
        try {
            long generation = Long.parseLong(key.substring(separator + 1));
            return generation > 0L ? withGeneration(observation, generation) : observation;
        } catch (NumberFormatException ignored) {
            return observation;
        }
    }

    private boolean alreadyClosed(ExecutionObservation observation) {
        if (closedStreams.containsKey(streamKey(observation))) return true;
        return observation.scope().generation() == 0L
                && activeStreamKeys.get(observation.scope().requestId()) == null
                && closedRequestIds.containsKey(observation.scope().requestId())
                && !"workflow.root.started".equals(observation.type())
                && !"workflow.root.rejected".equals(observation.type());
    }

    /**
     * Late callbacks are fenced for an hour, far beyond the request and provider timeout budget,
     * while keeping this process-local safety registry bounded for long-lived nodes.
     */
    private void purgeClosedRequests() {
        if (closedStreams.size() <= 10_000 && closedRequestIds.size() <= 10_000) return;
        Instant cutoff = Instant.now().minus(java.time.Duration.ofHours(1));
        closedStreams.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        closedRequestIds.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        trimOldest(closedStreams);
        trimOldest(closedRequestIds);
    }

    private void trimOldest(ConcurrentHashMap<String, Instant> tombstones) {
        if (tombstones.size() <= 10_000) return;
        tombstones.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(tombstones.size() - 9_000L)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(tombstones::remove);
    }

    private void notify(ExecutionEventListener listener, ExecutionObservation event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException failure) {
            LOGGER.warn("AI execution event listener {} failed for request {} event {}",
                    listener.getClass().getSimpleName(), event.scope().requestId(),
                    event.attributes().get(EVENT_ID), failure);
        }
    }

    @PreDestroy
    void closeExternalDrains() {
        if (externalDrains == null) return;
        externalDrains.shutdown();
        try {
            if (!externalDrains.awaitTermination(30, TimeUnit.SECONDS)) {
                int dropped = externalDrains.shutdownNow().size();
                LOGGER.warn("AI execution external shutdown discarded {} queued events", dropped);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            int dropped = externalDrains.shutdownNow().size();
            LOGGER.warn("AI execution external shutdown was interrupted; {} events were discarded",
                    dropped);
        }
    }

    private static final class RequestStream {
        private final AtomicLong sequence = new AtomicLong();
        private final ArrayDeque<Publication> beforeRoot = new ArrayDeque<>();
        private final ArrayDeque<Publication> whileClosing = new ArrayDeque<>();
        private final TreeMap<Long, Dispatch> pending = new TreeMap<>();
        private final TreeSet<Long> skipped = new TreeSet<>();
        private Instant lastOccurredAt;
        private long nextDispatchSequence = 1L;
        private long closingSequence;
        private boolean started;
        private boolean closed;
        private boolean draining;
    }

    private record Dispatch(ExecutionObservation event,
                            Consumer<ExecutionObservation> projection,
                            CompletableFuture<Void> causalCompletion) {
        private long sequence() {
            return ((Number) event.attributes().get(EVENT_SEQUENCE)).longValue();
        }
    }

    private record Publication(ExecutionObservation observation,
                               Consumer<ExecutionObservation> persistence,
                               Consumer<ExecutionObservation> projection) { }

}

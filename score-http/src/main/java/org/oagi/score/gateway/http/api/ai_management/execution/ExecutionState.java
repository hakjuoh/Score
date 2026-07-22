package org.oagi.score.gateway.http.api.ai_management.execution;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Mandatory run state kept separate from optional trajectory persistence. */
public final class ExecutionState {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong completedToolCalls = new AtomicLong();
    private final AtomicLong completedMutations = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    public boolean cancel() { return cancelled.compareAndSet(false, true); }
    public boolean cancelled() { return cancelled.get(); }
    public long toolCompleted(boolean mutation) {
        if (mutation) completedMutations.incrementAndGet();
        return completedToolCalls.incrementAndGet();
    }
    public long completedToolCalls() { return completedToolCalls.get(); }
    public long completedMutations() { return completedMutations.get(); }
    public long retryStarted() { return retries.incrementAndGet(); }
    public long retries() { return retries.get(); }
}

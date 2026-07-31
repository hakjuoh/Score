package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Tracks balanced request and workflow activity leases for data-changing tool executions. */
final class AiChangeExecutionLease {

    private final String requestId;
    private final AiRequestRegistry requests;
    private final WorkflowRunControl runControl;
    private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();

    AiChangeExecutionLease(String requestId, AiRequestRegistry requests,
                           WorkflowRunControl runControl) {
        this.requestId = requestId;
        this.requests = requests;
        this.runControl = runControl;
    }

    boolean acquire(String executionKey) {
        runControl.definiteActivityStarted();
        boolean registered = false;
        boolean tracked = false;
        try {
            registered = requests.changeStarted(requestId);
            if (!registered) return false;
            active.compute(executionKey, (ignored, leases) -> {
                AtomicInteger current = leases != null ? leases : new AtomicInteger();
                current.incrementAndGet();
                return current;
            });
            tracked = true;
            return true;
        } finally {
            if (!tracked) {
                try {
                    if (registered) requests.changeFinished(requestId);
                } finally {
                    runControl.definiteActivityFinished();
                }
            }
        }
    }

    void release(String executionKey) {
        AtomicBoolean released = new AtomicBoolean();
        active.computeIfPresent(executionKey, (ignored, leases) -> {
            released.set(true);
            return leases.decrementAndGet() <= 0 ? null : leases;
        });
        if (!released.get()) return;
        try {
            requests.changeFinished(requestId);
        } finally {
            runControl.definiteActivityFinished();
        }
    }
}
